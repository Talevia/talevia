package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.fork.persistFork
import io.talevia.core.tool.builtin.project.fork.regenerateTtsInLanguage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Branch a project into a new one (VISION §3.4 — "可分支").
 *
 * Snapshots cover save+restore on a single trunk; forking is the third leg —
 * "let me try a different cut without losing the original". The new project gets
 * a fresh [ProjectId] and a clean (empty) snapshots list, but inherits the source
 * payload — timeline, source DAG, lockfile, render cache, asset catalog ids,
 * output profile — from either the *current* state of the source project (when
 * `snapshotId` is null) or from a specific captured snapshot.
 *
 * Asset bytes are not duplicated. The fork's `Project.assets` list carries the
 * same `AssetId`s and `MediaSource` entries as the source; external paths and
 * bundle-relative paths stay as-is. If the user later mutates one project's
 * assets in place we'll need refcounting; for now the canonical mutation
 * pattern is "produce a new asset and clip_action(action="replace")", so the shared-id model
 * is safe.
 *
 * Fails loud on duplicate `newProjectId` so the agent reconciles via
 * `list_projects` rather than silently stomping a project the user already cares
 * about — same discipline as `create_project`.
 */
class ForkProjectTool(
    private val projects: ProjectStore,
    /**
     * Optional tool registry. Only consulted when `variantSpec.language` is
     * set — the fork dispatches the registered `synthesize_speech` tool per
     * text clip to generate target-language voiceovers against the fork's
     * project id. When null (test rigs, deployments without TTS), passing
     * `variantSpec.language` fails loud with a wiring hint rather than
     * silently skipping the regeneration.
     */
    private val registry: ToolRegistry? = null,
) : Tool<ForkProjectTool.Input, ForkProjectTool.Output> {

    @Serializable data class Input(
        val sourceProjectId: String,
        val newTitle: String,
        val newProjectId: String? = null,
        /**
         * If null → fork from the source project's *current* live state.
         * If set → fork from the captured snapshot with this id. The snapshot
         * must exist on the source project; we fail loudly otherwise.
         */
        val snapshotId: String? = null,
        /**
         * Optional variant spec — when set, applies a post-fork reshape to
         * the forked project's timeline + output profile. Used by
         * VISION §6 "30s / 竖版 variant" flows: instead of
         * `fork + set_output_profile + clip_action(trim)`, the caller passes a
         * `variantSpec` and the tool does the reshape in one step.
         * `null` → verbatim fork (no reshape, identical to pre-variant
         * behavior).
         */
        val variantSpec: VariantSpec? = null,
        /**
         * Optional filesystem path for the forked bundle. See
         * [CreateProjectTool.Input.path] for semantics.
         */
        val path: String? = null,
    )

    /**
     * Bounded set of post-fork reshape operations. Intentionally narrow:
     * anything more invasive (re-binding AIGC nodes, re-running TTS in a
     * different language) should stay in specialised tools because it
     * needs provider calls / LLM reasoning. This spec covers what can be
     * done as a pure in-memory transform.
     */
    @Serializable data class VariantSpec(
        /**
         * Target aspect ratio. Accepted presets (case-insensitive):
         * `"16:9"` (1920x1080), `"9:16"` (1080x1920), `"1:1"` (1080x1080),
         * `"4:5"` (1080x1350), `"21:9"` (2520x1080). Each preset sets
         * both `outputProfile.resolution` and `timeline.resolution`.
         * Engines render at the new aspect — per-clip transforms stay
         * untouched; the caller is expected to follow up with
         * re-framing tools if clips need per-clip reposition.
         */
        val aspectRatio: String? = null,
        /**
         * Cap the timeline at this many seconds. Clips starting at or
         * after the cap are dropped; clips straddling the cap get
         * truncated in both their timeline range and their source
         * range. Must be `> 0` when set.
         */
        val durationSecondsMax: Double? = null,
        /**
         * Optional ISO-639-1 language hint (e.g. `"en"`, `"es"`, `"zh"`).
         * When set, the fork dispatches `synthesize_speech` for every text
         * clip whose `text` is non-blank; the resulting audio assets are
         * recorded into the fork's lockfile (keyed by the same text plus
         * this language) so a second fork in the same language is a cache
         * hit. The fork's timeline is **not** rewired automatically — the
         * caller chains `clip_action(action="replace")` per entry in
         * [Output.languageRegeneratedClips] to swap existing audio. This
         * mirrors the "fork stays a reshape primitive" principle: we
         * surface side-effectful generations on the fork, not timeline
         * edits on the fork's audio tracks.
         */
        val language: String? = null,
    )

    /**
     * One TTS regeneration outcome produced by `variantSpec.language`. The
     * caller (usually the agent) wires the generated [assetId] onto the
     * appropriate audio clip via `clip_action(action="replace")` or `add_clip`; we leave that
     * bind explicit so the fork's timeline shape is never mutated without a
     * direct tool call the user can see in the transcript.
     *
     * @property clipId the text clip whose text drove the regeneration.
     * @property assetId the new audio asset id (may match a prior fork's
     *   asset if the lockfile cache-hit on `(text, voice, language, …)`).
     * @property cacheHit whether the underlying synthesize_speech call came
     *   back from the lockfile; useful when a user re-forks in the same
     *   language and expects no provider billing.
     */
    @Serializable
    data class LanguageRegenResult(
        val clipId: String,
        val assetId: String,
        val cacheHit: Boolean,
    )

    @Serializable data class Output(
        val sourceProjectId: String,
        val newProjectId: String,
        val newTitle: String,
        val branchedFromSnapshotId: String?,
        val clipCount: Int,
        val trackCount: Int,
        /** Echo of the variant spec that was applied (omitted when null). */
        val appliedVariantSpec: VariantSpec? = null,
        /** Final timeline resolution after aspect reframe (null when no reshape). */
        val variantResolutionWidth: Int? = null,
        val variantResolutionHeight: Int? = null,
        /** Number of clips dropped by `durationSecondsMax` trimming. */
        val clipsDroppedByTrim: Int = 0,
        /** Number of clips truncated by `durationSecondsMax` trimming. */
        val clipsTruncatedByTrim: Int = 0,
        /**
         * Per-text-clip TTS regenerations produced by
         * `variantSpec.language`. Empty / null when language wasn't set or
         * there were no text clips with non-blank `text`. Each entry's
         * `assetId` is the new audio the caller should bind via
         * `clip_action(action="replace")` (or `add_clip` on a voiceover track).
         */
        val languageRegeneratedClips: List<LanguageRegenResult> = emptyList(),
    )

    override val id: String = "fork_project"
    override val helpText: String =
        "Branch a project into a new one. Defaults to forking the source's current state; " +
            "pass snapshotId to fork a prior snapshot. New project has fresh id, empty " +
            "snapshots list, inherits timeline/source/lockfile/render-cache/assets/output-" +
            "profile. Asset bytes are not duplicated — shared AssetIds. " +
            "variantSpec={aspectRatio?, durationSecondsMax?, language?} reshapes in one " +
            "step: aspectRatio presets 16:9|9:16|1:1|4:5|21:9 remap resolution; " +
            "durationSecondsMax caps the timeline (drops tail clips, truncates straddlers); " +
            "language (ISO-639-1) dispatches synthesize_speech per non-blank text clip, " +
            "surfaces (clipId, assetId, cacheHit) in languageRegeneratedClips. " +
            "parentProjectId points at the source so variants form a lineage."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = FORK_PROJECT_INPUT_SCHEMA

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.newTitle.isNotBlank()) { "newTitle must not be blank" }
        val sourcePid = ProjectId(input.sourceProjectId)
        val source = projects.get(sourcePid)
            ?: error("Source project ${input.sourceProjectId} not found")

        val payload: Project = if (input.snapshotId == null) {
            source
        } else {
            val targetId = ProjectSnapshotId(input.snapshotId)
            source.snapshots.firstOrNull { it.id == targetId }?.project
                ?: error(
                    "Snapshot ${input.snapshotId} not found on project ${input.sourceProjectId}. " +
                        "Call list_project_snapshots to enumerate.",
                )
        }

        // Persistence + variant reshape lives in fork/ForkProjectPersist.kt
        // — handles the path / no-path branches uniformly and re-reads the
        // forked Project from the store so post-store stamping is visible.
        val persisted = persistFork(projects, sourcePid, payload, input)
        val newPid = persisted.pid
        val reshape = persisted.reshape
        val forked = persisted.forked

        val regenerations = input.variantSpec?.language?.let { lang ->
            regenerateTtsInLanguage(registry, newPid, forked, lang.trim(), ctx)
        } ?: emptyList()

        val clipCount = forked.timeline.tracks.sumOf { it.clips.size }
        val trackCount = forked.timeline.tracks.size
        val out = Output(
            sourceProjectId = sourcePid.value,
            newProjectId = newPid.value,
            newTitle = input.newTitle,
            branchedFromSnapshotId = input.snapshotId,
            clipCount = clipCount,
            trackCount = trackCount,
            appliedVariantSpec = input.variantSpec,
            variantResolutionWidth = reshape?.project?.timeline?.resolution?.width
                ?.takeIf { input.variantSpec?.aspectRatio != null },
            variantResolutionHeight = reshape?.project?.timeline?.resolution?.height
                ?.takeIf { input.variantSpec?.aspectRatio != null },
            clipsDroppedByTrim = reshape?.clipsDropped ?: 0,
            clipsTruncatedByTrim = reshape?.clipsTruncated ?: 0,
            languageRegeneratedClips = regenerations,
        )
        val from = input.snapshotId?.let { "snapshot $it" } ?: "current state"
        val variantNote = reshape?.let { r ->
            buildString {
                append(", variantSpec applied (")
                val parts = mutableListOf<String>()
                input.variantSpec?.aspectRatio?.let {
                    parts += "aspect=$it → ${forked.timeline.resolution.width}x${forked.timeline.resolution.height}"
                }
                input.variantSpec?.durationSecondsMax?.let {
                    parts += "duration≤${it}s (dropped ${r.clipsDropped}, truncated ${r.clipsTruncated})"
                }
                append(parts.joinToString(", "))
                append(")")
            }
        }.orEmpty()
        val regenNote = input.variantSpec?.language?.let { lang ->
            val hits = regenerations.count { it.cacheHit }
            val fresh = regenerations.size - hits
            ", regenerated TTS for ${regenerations.size} text clip(s) in lang=$lang " +
                "($fresh fresh, $hits cached)"
        }.orEmpty()
        return ToolResult(
            title = "fork project ${input.newTitle}",
            outputForLlm = "Forked project ${sourcePid.value} → ${newPid.value} " +
                "(\"${input.newTitle}\", from $from, $clipCount clip(s), $trackCount track(s)$variantNote$regenNote). " +
                "Pass projectId=${newPid.value} to subsequent tool calls on the fork.",
            data = out,
        )
    }
}
