package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.PathGuard
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.DurationUnit

/**
 * Register a local file as a project asset: probes via the engine, persists into
 * [MediaStorage]'s catalog, **and appends the probed [io.talevia.core.domain.MediaAsset]
 * to `Project.assets`** so downstream consumers (`project_query(select=assets)`,
 * `add_clip`'s `RequiresAssets` applicability, the lockfile discipline) actually
 * see it in the right place.
 *
 * Why both stores: [MediaStorage] is the global blob / proxy / metadata catalog
 * (cross-project, survives project deletion); [Project.assets] is the per-project
 * inventory the UI lists and the timeline binds clips to. Earlier the tool only
 * wrote the former, so `import_media` succeeded but every asset-scoped follow-up
 * ("which assets does this project have?", "add this imported clip to the
 * timeline") silently failed.
 */
class ImportMediaTool(
    private val storage: MediaStorage,
    private val engine: VideoEngine,
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<ImportMediaTool.Input, ImportMediaTool.Output> {

    @Serializable data class Input(
        val path: String,
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
    )
    @Serializable data class Output(
        val projectId: String,
        val assetId: String,
        val durationSeconds: Double,
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        /** Total assets in the project AFTER this import — a quick sanity signal for the agent. */
        val projectAssetCount: Int,
    )

    override val id = "import_media"
    override val helpText =
        "Import a media file by path: probes its metadata, registers it with MediaStorage, and " +
            "appends the asset to the current project's inventory. Returns the new assetId so you " +
            "can `add_clip(assetId=…)` immediately after. Defaults projectId from the session " +
            "binding — pass it only when importing into a non-current project."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("media.import")
    // Import needs a project binding to know which project.assets to append to.
    // `list_projects` / `create_project` / `switch_project` remain Always so the
    // agent can always get to a binding before trying to import.
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path to a video / image / audio file on the local filesystem.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to import into the session's current project (set via switch_project).",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        // Import reads from an arbitrary path on disk — reject traversal before we
        // pass it to the platform storage layer.
        PathGuard.validate(input.path, requireAbsolute = true)
        val pid = ctx.resolveProjectId(input.projectId)

        val asset = storage.import(MediaSource.File(input.path)) { source -> engine.probe(source) }
        val stamped = asset.copy(updatedAtEpochMs = clock.now().toEpochMilliseconds())

        // Append into Project.assets. Dedupe by assetId so re-importing the same
        // file is idempotent (MediaStorage's own dedup returns the prior asset id
        // byte-for-byte; mirroring that here keeps the project list stable).
        val updated = projects.mutate(pid) { project ->
            val existing = project.assets.any { it.id == stamped.id }
            if (existing) project
            else project.copy(assets = project.assets + stamped)
        }

        val out = Output(
            projectId = pid.value,
            assetId = stamped.id.value,
            durationSeconds = stamped.metadata.duration.toDouble(DurationUnit.SECONDS),
            width = stamped.metadata.resolution?.width,
            height = stamped.metadata.resolution?.height,
            videoCodec = stamped.metadata.videoCodec,
            audioCodec = stamped.metadata.audioCodec,
            projectAssetCount = updated.assets.size,
        )
        return ToolResult(
            title = "import ${input.path.substringAfterLast('/')}",
            outputForLlm = "Imported asset ${out.assetId} (${out.durationSeconds}s, ${out.width}x${out.height}) " +
                "into project ${pid.value}; project now has ${out.projectAssetCount} asset(s).",
            data = out,
        )
    }
}
