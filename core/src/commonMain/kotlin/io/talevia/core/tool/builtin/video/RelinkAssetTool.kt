package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
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

/**
 * Flip a `MediaSource.File(absolutePath)` asset to point at a new on-disk
 * location, cascading to every other asset that referenced the same
 * original path. Answers the cross-machine flow the
 * `bundle-asset-relink-ux` bullet calls out: bob opens alice's bundle,
 * `BusEvent.AssetsMissing` surfaces `(assetId, originalPath)`, bob
 * provides a newPath, and every other asset that pointed at the same
 * missing file gets fixed in one call.
 *
 * Cascade semantics: the tool looks up `assetId`'s current
 * `MediaSource.File.path`, then walks `project.assets` and updates EVERY
 * asset whose source equals that same absolute path. This matches the
 * typical real-world case: a user imported multiple clips off one
 * recording, all share the same footage path on alice's machine, and
 * bob needs to relink them collectively. The caller picks any one of
 * those asset ids to trigger the cascade.
 *
 * Does NOT touch clips' `sourceBinding` (those point at source DAG
 * nodes, not asset files); does NOT flip `BundleFile` / `Http` /
 * `Platform` sources (those are already portable or aren't
 * filesystem-based). Asset whose source is non-`File` → loud error.
 *
 * Permission: `"media.import"` — same bucket as `import_media` /
 * `consolidate_media_into_bundle`; all three rewire the asset catalog.
 */
class RelinkAssetTool(
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<RelinkAssetTool.Input, RelinkAssetTool.Output> {

    @Serializable data class Input(
        /** Asset id whose `MediaSource.File` path is out of date. */
        val assetId: String,
        /**
         * New absolute filesystem path the caller wants the asset (and
         * every sibling that shared the same original path) to point at.
         * Must be non-blank; no validation that the file exists — the
         * tool is deliberately lax so a user can pre-relink before the
         * file is actually copied into place. Post-relink export will
         * fail at render time if still missing; that's the backstop.
         */
        val newPath: String,
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud directs the agent at `switch_project`.
         */
        val projectId: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        /** Original path every relinked asset used to point at. */
        val originalPath: String,
        val newPath: String,
        /** Every asset id that flipped this call (≥ 1; the requested id plus any cascade siblings). */
        val relinkedAssetIds: List<String>,
    )

    override val id: String = "relink_asset"
    override val helpText: String =
        "Rewrite a MediaSource.File asset's absolute path to a new location, cascading to " +
            "every sibling asset that referenced the same original path. Use after opening a " +
            "bundle from another machine when BusEvent.AssetsMissing reports the original " +
            "paths don't resolve locally — one relink_asset call can fix every clip sharing " +
            "the same source footage. Lax on file existence (doesn't verify newPath resolves) " +
            "so you can pre-relink before copying the file into place; export will fail at " +
            "render time if still missing."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("media.import")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("assetId") {
                put("type", "string")
                put("description", "Asset id of a MediaSource.File asset whose path needs to be rewired.")
            }
            putJsonObject("newPath") {
                put("type", "string")
                put(
                    "description",
                    "New absolute filesystem path. Every asset sharing the same original path also " +
                        "flips to this value (cascade).",
                )
            }
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("assetId"), JsonPrimitive("newPath"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.newPath.isNotBlank()) { "relink_asset: newPath must be non-blank." }
        val pid = ctx.resolveProjectId(input.projectId)
        val targetId = AssetId(input.assetId)

        val project = projects.get(pid) ?: error("Project ${pid.value} not found")
        val target = project.assets.firstOrNull { it.id == targetId }
            ?: error(
                "Asset ${input.assetId} not found in project ${pid.value}. Call " +
                    "project_query(select=assets) to discover valid ids.",
            )
        val originalSrc = target.source
        if (originalSrc !is MediaSource.File) {
            error(
                "Asset ${input.assetId} is not a MediaSource.File (source=${originalSrc::class.simpleName}). " +
                    "relink_asset only rewires File sources; BundleFile assets travel with the bundle, " +
                    "Http/Platform sources have their own paths.",
            )
        }
        val originalPath = originalSrc.path

        val relinked = mutableListOf<String>()
        val now = clock.now().toEpochMilliseconds()
        projects.mutate(pid) { prior ->
            val assetsAfter = prior.assets.map { a ->
                val src = a.source
                if (src is MediaSource.File && src.path == originalPath) {
                    relinked += a.id.value
                    a.copy(
                        source = MediaSource.File(input.newPath),
                        updatedAtEpochMs = now,
                    )
                } else {
                    a
                }
            }
            prior.copy(assets = assetsAfter)
        }

        val out = Output(
            projectId = pid.value,
            originalPath = originalPath,
            newPath = input.newPath,
            relinkedAssetIds = relinked,
        )
        val tail = if (relinked.size == 1) "" else " (+ ${relinked.size - 1} sibling(s) with same original path)"
        return ToolResult(
            title = "relink ${relinked.size} asset(s)",
            outputForLlm = "Relinked ${relinked.size} asset(s) in project ${pid.value}: " +
                "$originalPath → ${input.newPath}$tail.",
            data = out,
        )
    }
}
