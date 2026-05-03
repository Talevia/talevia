package io.talevia.core.tool.builtin.project

import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Six-way project lifecycle verb — consolidates `CreateProjectTool` +
 * `OpenProjectTool` + `DeleteProjectTool` + `RenameProjectTool` +
 * `SetOutputProfileTool` + `RemoveAssetTool`
 * (`debt-consolidate-project-lifecycle-tools`, 2026-04-24).
 *
 * Per-action contract summary (details in each sibling handler's KDoc):
 *
 * - `action="create"` (permission `project.write`) — bootstrap a fresh
 *   project (empty timeline / assets / source DAG). Required: `title`.
 *   Optional: `projectId` (default = slug of title), `resolutionPreset`
 *   (`720p` / `1080p` / `4k`, default `1080p`), `fps` (`24` / `30` /
 *   `60`, default `30`), `path` (default `<projectsHome>/<id>/`).
 * - `action="open"` (permission `project.read`) — register an existing
 *   bundle on this machine. Required: `path` (directory containing
 *   `talevia.json`).
 * - `action="delete"` (permission `project.destructive`) — drop a
 *   project from the recents registry; with `deleteFiles=true`, also
 *   wipe the on-disk bundle. Required: `projectId`. Optional:
 *   `deleteFiles` (default `false`).
 * - `action="rename"` (permission `project.write`) — change a project's
 *   title without touching the underlying model. Required: `projectId`,
 *   `title`.
 * - `action="set_output_profile"` (permission `project.write`) — patch
 *   the render target's `OutputProfile` (every field optional, null =
 *   keep). Required: `projectId` and at least one profile field.
 *   Optional: `resolutionWidth` + `resolutionHeight` (paired), `fps`,
 *   `videoCodec`, `audioCodec`, `videoBitrate`, `audioBitrate`,
 *   `container`.
 * - `action="remove_asset"` (permission `project.write`) — drop an
 *   asset from `project.assets`. Refuses by default if any clip
 *   references the asset; `force=true` removes anyway leaving dangling
 *   clips for `project_query(select=validation)` to surface. Required: `projectId`,
 *   `assetId`. Optional: `force` (default `false`).
 *
 * Permission base tier is `project.write` (the most common).
 * [PermissionSpec.permissionFrom] downgrades to `project.read` for
 * `open` and upgrades to `project.destructive` for `delete` by parsing
 * the action string out of the raw input JSON. Anything that fails
 * parsing falls through to the base tier — the default never dips
 * below `project.write` so a malformed input cannot sneak past a more
 * destructive gate.
 *
 * Handler bodies live as siblings — one file per verb following
 * `ProjectMaintenanceActionTool`'s convention (each verb's preconditions
 * + business logic stay isolated):
 *
 * - [executeCreateProject] — `CreateProjectHandler.kt`
 * - [executeOpenProject] — `OpenProjectHandler.kt`
 * - [executeDeleteProject] — `DeleteProjectHandler.kt`
 * - [executeRenameProject] — `RenameProjectHandler.kt`
 * - [executeSetOutputProfile] — `SetOutputProfileHandler.kt`
 * - [executeRemoveAsset] — `RemoveAssetHandler.kt`
 *
 * The class itself carries only the LLM-facing surface: nested data
 * classes for input / per-action output payloads, the JSON schema
 * (extracted to `ProjectLifecycleActionToolSchema.kt`), the tool metadata, the
 * permission dispatcher, and the six-way `when` in [execute].
 */
class ProjectLifecycleActionTool(
    private val projects: ProjectStore,
    /**
     * Optional session store. When provided, `action="create"` /
     * `"create_from_template"` automatically rebind the dispatching session
     * to the freshly-created project (see [autoBindSessionToProject] for
     * why this bypasses `switch_project`). Null keeps the tool usable in
     * test rigs that don't subscribe — the project is still created but
     * the session keeps its previous binding (or remains unbound).
     * Production composition roots all pass the app's session store.
     */
    private val sessions: SessionStore? = null,
    private val clock: Clock = Clock.System,
) : Tool<ProjectLifecycleActionTool.Input, ProjectLifecycleActionTool.Output> {

    @Serializable data class Input(
        /**
         * One of `"create"`, `"create_from_template"`, `"open"`,
         * `"delete"`, `"rename"`, `"set_output_profile"`,
         * `"remove_asset"`. Case-sensitive.
         */
        val action: String,
        /**
         * Project identity. Required for delete / rename /
         * set_output_profile / remove_asset. Optional naming hint for
         * create (defaults to a slug of `title`). Ignored by open
         * (the id is read from the bundle's `talevia.json`).
         */
        val projectId: String? = null,
        /** create: initial title. rename: new title. */
        val title: String? = null,
        /**
         * create + open: filesystem path for the bundle. create:
         * optional location (omit to use the store's default home).
         * open: required path to an existing bundle directory.
         */
        val path: String? = null,
        /** create only. `720p` / `1080p` (default) / `4k`. */
        val resolutionPreset: String? = null,
        /** create + set_output_profile. Integer frames per second. */
        val fps: Int? = null,
        /** delete only. When true, also delete the on-disk bundle. Default false. */
        val deleteFiles: Boolean = false,
        /** remove_asset only. Asset id to drop. */
        val assetId: String? = null,
        /** remove_asset only. Force-remove even if clips still reference the asset. */
        val force: Boolean = false,
        /** set_output_profile only. Pair with resolutionHeight. */
        val resolutionWidth: Int? = null,
        /** set_output_profile only. Pair with resolutionWidth. */
        val resolutionHeight: Int? = null,
        /** set_output_profile only. e.g. h264 / h265 / prores / vp9. */
        val videoCodec: String? = null,
        /** set_output_profile only. e.g. aac / opus / mp3. */
        val audioCodec: String? = null,
        /** set_output_profile only. Bits per second. */
        val videoBitrate: Long? = null,
        /** set_output_profile only. Bits per second. */
        val audioBitrate: Long? = null,
        /** set_output_profile only. e.g. mp4 / mov / mkv / webm. */
        val container: String? = null,
        /**
         * `create_from_template` only — required. Genre template id:
         * `"narrative"` | `"vlog"` | `"ad"` | `"musicmv"` | `"tutorial"` |
         * `"auto"`. `"auto"` requires [intent] and drives keyword-based
         * classification on-device.
         */
        val template: String? = null,
        /**
         * `create_from_template` only — required when
         * `template = "auto"`, ignored otherwise. One-sentence user
         * intent the on-device keyword classifier reads to pick a
         * genre. No LLM round-trip.
         */
        val intent: String? = null,
    )

    @Serializable data class CreateResult(
        val title: String,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
    )

    @Serializable data class OpenResult(
        val title: String,
    )

    @Serializable data class DeleteResult(
        val title: String,
        /** True iff the on-disk bundle was also wiped. */
        val filesDeleted: Boolean = false,
        /** Path that was (or would have been) deleted, when known. */
        val path: String? = null,
    )

    @Serializable data class RenameResult(
        val previousTitle: String,
        val title: String,
    )

    @Serializable data class SetOutputProfileResult(
        val updatedFields: List<String>,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
        val videoCodec: String,
        val audioCodec: String,
        val videoBitrate: Long,
        val audioBitrate: Long,
        val container: String,
    )

    @Serializable data class RemoveAssetResult(
        val assetId: String,
        val removed: Boolean,
        /**
         * Clip ids that still reference the asset. Non-empty only when
         * `force=true` left dangling clips, or in the error path.
         */
        val dependentClips: List<String>,
    )

    @Serializable data class CreateFromTemplateResult(
        val title: String,
        /** Echo of the template actually seeded (resolved from `auto` when applicable). */
        val template: String,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
        val seededNodeIds: List<String>,
        /**
         * `true` when the caller passed `template = "auto"` and the
         * keyword classifier picked the resolved template.
         */
        val inferredFromIntent: Boolean = false,
        /** Brief explanation of which keywords drove classification. */
        val inferredReason: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        /** Populated when `action="create"`. */
        val createResult: CreateResult? = null,
        /** Populated when `action="create_from_template"`. */
        val createFromTemplateResult: CreateFromTemplateResult? = null,
        /** Populated when `action="open"`. */
        val openResult: OpenResult? = null,
        /** Populated when `action="delete"`. */
        val deleteResult: DeleteResult? = null,
        /** Populated when `action="rename"`. */
        val renameResult: RenameResult? = null,
        /** Populated when `action="set_output_profile"`. */
        val setOutputProfileResult: SetOutputProfileResult? = null,
        /** Populated when `action="remove_asset"`. */
        val removeAssetResult: RemoveAssetResult? = null,
    )

    override val id: String = "project_lifecycle_action"
    override val helpText: String =
        "Seven-way project lifecycle verb dispatching on `action`: " +
            "`create` + title (resolutionPreset?, fps?, projectId?, path?) bootstraps a project " +
            "(permission `project.write`). " +
            "`create_from_template` + title + template (narrative|vlog|ad|musicmv|tutorial|auto) " +
            "(intent? required when template=auto, projectId?, path?, resolutionPreset?, fps?) " +
            "creates a project pre-populated with a genre source-DAG skeleton. Each genre's " +
            "seed body uses TODO placeholders so the agent / user can tell what still needs " +
            "filling in. template=auto + intent classifies via on-device keyword match (no LLM " +
            "round-trip). VISION §5.4 novice path. " +
            "`open` + path registers an existing bundle (permission `project.read`). " +
            "`delete` + projectId (deleteFiles?) drops the project; deleteFiles=true also wipes " +
            "the on-disk bundle (permission `project.destructive`). " +
            "`rename` + projectId + title changes the human label only. " +
            "`set_output_profile` + projectId + ≥1 profile field (resolutionWidth+resolutionHeight " +
            "paired, fps, videoCodec, audioCodec, videoBitrate, audioBitrate, container) patches " +
            "the render target. " +
            "`remove_asset` + projectId + assetId (force?) drops an asset; refuses by default if " +
            "any clip references it. " +
            "set_output_profile leaves the timeline's authoring resolution untouched."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()

    /**
     * Base tier `project.write` (most common). [permissionFrom]
     * downgrades to `project.read` for `open` and upgrades to
     * `project.destructive` for `delete`. Anything that fails to
     * parse stays at the base tier — never dips below
     * `project.write`, so a malformed input cannot bypass a stricter
     * gate.
     */
    override val permission: PermissionSpec = PermissionSpec(
        permission = "project.write",
        permissionFrom = { inputJson ->
            when {
                isOpenAction(inputJson) -> "project.read"
                isDeleteAction(inputJson) -> "project.destructive"
                else -> "project.write"
            }
        },
    )

    override val inputSchema: JsonObject = PROJECT_ACTION_INPUT_SCHEMA

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        return when (input.action) {
            "create" -> executeCreateProject(projects, sessions, clock, input, ctx)
            "create_from_template" -> executeCreateFromTemplate(projects, sessions, clock, input, ctx)
            "open" -> executeOpenProject(projects, input, ctx)
            "delete" -> executeDeleteProject(projects, input, ctx)
            "rename" -> executeRenameProject(projects, input, ctx)
            "set_output_profile" -> executeSetOutputProfile(projects, input, ctx)
            "remove_asset" -> executeRemoveAsset(projects, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: create, create_from_template, " +
                    "open, delete, rename, set_output_profile, remove_asset",
            )
        }
    }

    private companion object {
        /**
         * Lightweight pre-decode scans for the `permissionFrom` dispatcher.
         * Each runs before kotlinx.serialization decode, so we cannot
         * assume well-formed input. Failure to match falls back to the
         * base `project.write` tier — never below — so destructive intent
         * cannot sneak past via a malformed payload.
         */
        private val OPEN_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"open"""",
            option = RegexOption.IGNORE_CASE,
        )
        private val DELETE_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"delete"""",
            option = RegexOption.IGNORE_CASE,
        )

        fun isOpenAction(inputJson: String): Boolean = OPEN_ACTION_REGEX.containsMatchIn(inputJson)
        fun isDeleteAction(inputJson: String): Boolean = DELETE_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
