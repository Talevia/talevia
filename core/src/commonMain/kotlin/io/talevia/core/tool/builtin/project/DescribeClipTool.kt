package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform
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
 * Per-clip deep inspection — the counterpart of [DescribeLockfileEntryTool]
 * on the timeline side. `project_query(select=timeline_clips)` gives a paginated bird's-eye
 * view; `describe_clip` returns every knob on a single clip plus derived
 * lockfile / staleness / pin signals computed against the project's current
 * state.
 *
 * Motivation: the expert-path editing flow typically reads as "I'm looking
 * at this one clip and want to know everything about it before I touch it."
 * The present workaround was a loop of:
 *  - `project_query(select=timeline_clips)` to find the clip + track ids,
 *  - `list_lockfile_entries` to find the lockfile row by assetId,
 *  - `find_stale_clips` / `project_query(select=timeline_clips, onlyPinned=true)` to check lane status,
 *  - manual cross-ref.
 * This tool does the resolution once and returns the composite.
 *
 * Per-clip-kind fields are modelled as nullable fields on a flat `Output`
 * rather than a sealed subtype because tool outputs are serialized to a
 * single JSON shape for the LLM — a nullable-field flat record is the
 * JSON-schema-friendly shape every OpenAI / Anthropic function-call
 * consumer handles without ceremony. The `clipType` discriminator tells
 * callers which fields to expect.
 *
 * Read-only; permission `project.read`.
 */
class DescribeClipTool(
    private val projects: ProjectStore,
) : Tool<DescribeClipTool.Input, DescribeClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
    )

    @Serializable data class TimeRange(
        val startMs: Long,
        val durationMs: Long,
        val endMs: Long,
    )

    @Serializable data class LockfileRef(
        val inputHash: String,
        val toolId: String,
        val pinned: Boolean,
        val currentlyStale: Boolean,
        /** Node ids whose current contentHash no longer matches the snapshotted value. */
        val driftedSourceNodeIds: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val clipId: String,
        val trackId: String,
        /** `"video"` | `"audio"` | `"text"`. */
        val clipType: String,
        val timeRange: TimeRange,
        /** Media-backed clips only (null for text). */
        val sourceRange: TimeRange? = null,
        val sourceBindingIds: List<String>,
        val transforms: List<Transform>,
        // Video / audio:
        val assetId: String? = null,
        // Video only:
        val filters: List<Filter>? = null,
        // Audio only:
        val volume: Float? = null,
        val fadeInSeconds: Float? = null,
        val fadeOutSeconds: Float? = null,
        // Text only:
        val text: String? = null,
        val textStyle: TextStyle? = null,
        /**
         * Lockfile resolution for the asset-backed clips. Null for text clips
         * and for imported-media clips whose asset has no lockfile entry.
         */
        val lockfile: LockfileRef? = null,
    )

    override val id: String = "describe_clip"
    override val helpText: String =
        "One-stop read for a single clip: timeRange, sourceRange, transforms, sourceBinding, " +
            "plus kind-specific fields (video filters, audio volume/fade, text style) and a " +
            "derived lockfile ref with pin + staleness status. Use before editing a clip to " +
            "see everything the agent might need to reason about in one call. For the lockfile " +
            "view use describe_lockfile_entry."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val cid = ClipId(input.clipId)

        val (track, clip) = findClip(project.timeline.tracks, cid)
            ?: error(
                "Clip ${input.clipId} not found in project ${input.projectId}. Call project_query(select=timeline_clips) " +
                    "to discover valid clip ids.",
            )

        val tr = TimeRange(
            startMs = clip.timeRange.start.inWholeMilliseconds,
            durationMs = clip.timeRange.duration.inWholeMilliseconds,
            endMs = clip.timeRange.end.inWholeMilliseconds,
        )
        val sr = clip.sourceRange?.let {
            TimeRange(
                startMs = it.start.inWholeMilliseconds,
                durationMs = it.duration.inWholeMilliseconds,
                endMs = it.end.inWholeMilliseconds,
            )
        }

        val assetIdValue: AssetId? = when (clip) {
            is Clip.Video -> clip.assetId
            is Clip.Audio -> clip.assetId
            is Clip.Text -> null
        }
        val lockfileRef = assetIdValue?.let { aid ->
            val entry = project.lockfile.findByAssetId(aid) ?: return@let null
            val currentHashesById = project.source.nodes.associate { it.id.value to it.contentHash }
            val drifted = entry.sourceContentHashes.filter { (nodeId, snap) ->
                val current = currentHashesById[nodeId.value]
                current == null || current != snap
            }.map { it.key.value }.sorted()
            LockfileRef(
                inputHash = entry.inputHash,
                toolId = entry.toolId,
                pinned = entry.pinned,
                currentlyStale = drifted.isNotEmpty(),
                driftedSourceNodeIds = drifted,
            )
        }

        val out = Output(
            projectId = pid.value,
            clipId = cid.value,
            trackId = track.id.value,
            clipType = when (clip) {
                is Clip.Video -> "video"
                is Clip.Audio -> "audio"
                is Clip.Text -> "text"
            },
            timeRange = tr,
            sourceRange = sr,
            sourceBindingIds = clip.sourceBinding.map { it.value }.sorted(),
            transforms = clip.transforms,
            assetId = assetIdValue?.value,
            filters = (clip as? Clip.Video)?.filters,
            volume = (clip as? Clip.Audio)?.volume,
            fadeInSeconds = (clip as? Clip.Audio)?.fadeInSeconds,
            fadeOutSeconds = (clip as? Clip.Audio)?.fadeOutSeconds,
            text = (clip as? Clip.Text)?.text,
            textStyle = (clip as? Clip.Text)?.style,
            lockfile = lockfileRef,
        )

        val staleNote = when {
            lockfileRef == null -> ""
            lockfileRef.currentlyStale -> " — stale"
            else -> " — fresh"
        }
        val pinNote = if (lockfileRef?.pinned == true) " — pinned" else ""
        val summary = "${out.clipType} clip ${out.clipId} on track ${out.trackId} " +
            "(${tr.durationMs / 1000.0}s)$staleNote$pinNote."
        return ToolResult(
            title = "describe clip ${out.clipId}",
            outputForLlm = summary,
            data = out,
        )
    }

    private fun findClip(tracks: List<Track>, clipId: ClipId): Pair<Track, Clip>? {
        for (track in tracks) {
            val clip = track.clips.firstOrNull { it.id == clipId } ?: continue
            return track to clip
        }
        return null
    }
}
