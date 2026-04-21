package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
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
 * Complement of [FindStaleClipsTool] on the pin lane — list every clip whose
 * current asset is backed by a pinned lockfile entry. VISION §3.1 "产物可 pin"
 * surfaced pinning at the lockfile layer and cycle-2 of the last loop added
 * the clip-level shortcuts; neither answered "show me every shot I've frozen"
 * on the timeline. The workaround was
 * `list_lockfile_entries` → filter-client-side → cross-ref each assetId to a
 * clip via `describe_project`. Two round-trips per audit.
 *
 * This tool walks the timeline once, resolves each media clip's assetId to a
 * lockfile entry via `Lockfile.findByAssetId` (same most-recent-match
 * semantics `regenerate_stale_clips` uses), and returns one report per
 * pinned clip.
 *
 * Scope — clips only. A pinned lockfile entry whose asset isn't currently on
 * the timeline is *not* reported here; that's what `list_lockfile_entries`
 * plus a `pinned` field filter is for (and the field is already exposed from
 * cycle 1 of the previous loop). The tool's job is the expert-path
 * "currently-pinned shots in my edit" query.
 *
 * Imported media and text clips are excluded by construction (no lockfile
 * entry to be pinned). Read-only; permission `project.read`.
 */
class FindPinnedClipsTool(
    private val projects: ProjectStore,
) : Tool<FindPinnedClipsTool.Input, FindPinnedClipsTool.Output> {

    @Serializable data class Input(val projectId: String)

    @Serializable data class Report(
        val clipId: String,
        val trackId: String,
        val assetId: String,
        val inputHash: String,
        val toolId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val pinnedClipCount: Int,
        val totalMediaClipCount: Int,
        val reports: List<Report>,
    )

    override val id: String = "find_pinned_clips"
    override val helpText: String =
        "List clips whose backing lockfile entry is pinned — the expert-path \"show me every " +
            "hero shot in this edit\" query. Complements find_stale_clips (what changed since " +
            "generation) on the pin lane (what is locked against regeneration). Walks the " +
            "timeline and cross-refs each media clip's assetId to Lockfile.findByAssetId. " +
            "Text clips and imported media are excluded — no lockfile entry to pin. Read-only."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")

        val reports = mutableListOf<Report>()
        var totalMedia = 0
        for (track in project.timeline.tracks) {
            for (clip in track.clips) {
                val assetId = when (clip) {
                    is Clip.Video -> clip.assetId
                    is Clip.Audio -> clip.assetId
                    is Clip.Text -> continue
                }
                totalMedia++
                val entry = project.lockfile.findByAssetId(assetId) ?: continue
                if (!entry.pinned) continue
                reports += Report(
                    clipId = clip.id.value,
                    trackId = track.id.value,
                    assetId = assetId.value,
                    inputHash = entry.inputHash,
                    toolId = entry.toolId,
                )
            }
        }

        val out = Output(
            projectId = pid.value,
            pinnedClipCount = reports.size,
            totalMediaClipCount = totalMedia,
            reports = reports,
        )
        val summary = when {
            totalMedia == 0 -> "Project ${pid.value} has no media clips."
            reports.isEmpty() -> "No pinned clips on project ${pid.value} ($totalMedia media clip(s) total)."
            else ->
                "${reports.size} pinned clip(s) of $totalMedia on project ${pid.value}: " +
                    reports.take(5).joinToString("; ") { "${it.clipId} (${it.toolId}/${it.assetId})" } +
                    if (reports.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "find pinned clips x${reports.size}",
            outputForLlm = summary,
            data = out,
        )
    }
}
