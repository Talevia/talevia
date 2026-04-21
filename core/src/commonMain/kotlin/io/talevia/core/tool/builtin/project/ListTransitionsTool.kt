package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Enumerate transitions on the project's timeline — the missing
 * companion to [ListTimelineClipsTool] for the transition slice.
 *
 * `AddTransitionTool` parks each transition as a synthetic [Clip.Video]
 * on a [Track.Effect] track, with `assetId = "transition:<name>"` and
 * the engine-side transition parameters recorded in `Clip.filters[0]`.
 * `list_timeline_clips` would return them, but the agent then has to
 * filter-and-parse the assetId prefix to separate transitions from
 * other effect-track clips. That works, but it leaks the encoding into
 * every caller — each one re-implements "is this clip a transition?"
 * and they drift apart.
 *
 * This tool centralises that projection. It reports each transition's
 * `transitionName`, start/duration on the timeline, and — crucially —
 * the two video clip ids it straddles (the `(fromClipId, toClipId)`
 * pair the agent passed to `add_transition`, recovered by looking at
 * the clips whose boundaries meet at the transition's midpoint).
 * `fromClipId` / `toClipId` may be null when the flanking clips were
 * later removed, leaving the transition orphaned — useful signal for
 * the agent to GC with `remove_transition`.
 *
 * Read-only, `project.read` — cheap to call during planning.
 */
class ListTransitionsTool(
    private val projects: ProjectStore,
) : Tool<ListTransitionsTool.Input, ListTransitionsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
    )

    @Serializable data class TransitionInfo(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
        val startSeconds: Double,
        val durationSeconds: Double,
        val endSeconds: Double,
        /** Video clip whose end meets (±1 frame at 30fps) the transition's start. Null if orphaned. */
        val fromClipId: String? = null,
        /** Video clip whose start meets (±1 frame at 30fps) the transition's end. Null if orphaned. */
        val toClipId: String? = null,
        /** When both flanking clips are missing the transition still renders nothing — flag it. */
        val orphaned: Boolean = false,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalTransitionCount: Int,
        val orphanedCount: Int,
        val transitions: List<TransitionInfo>,
    )

    override val id: String = "list_transitions"
    override val helpText: String =
        "List transitions on the project's timeline. Each row: transitionClipId, name, start/duration, " +
            "and the fromClipId/toClipId pair it joins (null when the flanking clips are missing — " +
            "flagged as orphaned for GC). Use this to answer 'where are the fades?' and to find stale " +
            "transitions whose video clips were later removed."
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
        val project = projects.get(ProjectId(input.projectId))
            ?: error("Project ${input.projectId} not found")

        val videoTracks = project.timeline.tracks.filterIsInstance<Track.Video>()
        val effectTracks = project.timeline.tracks.filterIsInstance<Track.Effect>()

        val rows = effectTracks.flatMap { track ->
            track.clips.mapNotNull { clip ->
                if (clip !is Clip.Video) return@mapNotNull null
                if (!clip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)) return@mapNotNull null
                val name = clip.filters.firstOrNull()?.name
                    ?: clip.assetId.value.removePrefix(TRANSITION_ASSET_PREFIX)
                val start = clip.timeRange.start
                val end = clip.timeRange.end
                val midpoint = start + (end - start) / 2

                val fromClip = videoTracks.findClipEndingNear(midpoint)
                val toClip = videoTracks.findClipStartingNear(midpoint)
                val orphaned = fromClip == null && toClip == null

                TransitionInfo(
                    transitionClipId = clip.id.value,
                    trackId = track.id.value,
                    transitionName = name,
                    startSeconds = start.toSecondsDouble(),
                    durationSeconds = clip.timeRange.duration.toSecondsDouble(),
                    endSeconds = end.toSecondsDouble(),
                    fromClipId = fromClip?.id?.value,
                    toClipId = toClip?.id?.value,
                    orphaned = orphaned,
                )
            }
        }.sortedBy { it.startSeconds }

        val orphanCount = rows.count { it.orphaned }
        val out = Output(
            projectId = input.projectId,
            totalTransitionCount = rows.size,
            orphanedCount = orphanCount,
            transitions = rows,
        )

        val body = if (rows.isEmpty()) {
            "No transitions on this timeline."
        } else {
            rows.joinToString("\n") { r ->
                val pair = when {
                    r.orphaned -> "orphaned"
                    r.fromClipId != null && r.toClipId != null -> "${r.fromClipId} → ${r.toClipId}"
                    r.fromClipId != null -> "${r.fromClipId} → (missing)"
                    r.toClipId != null -> "(missing) → ${r.toClipId}"
                    else -> "?"
                }
                "- ${r.transitionName} ${r.transitionClipId} @ ${r.startSeconds}s +${r.durationSeconds}s [$pair]"
            }
        }

        return ToolResult(
            title = "list transitions (${rows.size})",
            outputForLlm = "Project ${input.projectId}: ${rows.size} transition(s), $orphanCount orphaned.\n$body",
            data = out,
        )
    }

    /**
     * Find the Video clip whose `timeRange.end` is within [EPSILON] of [midpoint].
     * Transitions are centred on the cut between two adjacent clips, so for a
     * transition at midpoint M the preceding clip ends at M (by construction in
     * AddTransitionTool); we allow a one-frame-at-30fps slop to survive rounding.
     */
    private fun List<Track.Video>.findClipEndingNear(midpoint: Duration): Clip.Video? =
        asSequence()
            .flatMap { it.clips.asSequence() }
            .filterIsInstance<Clip.Video>()
            .filter { clip -> !clip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX) }
            .firstOrNull { (it.timeRange.end - midpoint).absoluteValue <= EPSILON }

    private fun List<Track.Video>.findClipStartingNear(midpoint: Duration): Clip.Video? =
        asSequence()
            .flatMap { it.clips.asSequence() }
            .filterIsInstance<Clip.Video>()
            .filter { clip -> !clip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX) }
            .firstOrNull { (it.timeRange.start - midpoint).absoluteValue <= EPSILON }

    private fun Duration.toSecondsDouble(): Double = inWholeMilliseconds / 1000.0

    companion object {
        private const val TRANSITION_ASSET_PREFIX = "transition:"

        /** One frame at 30fps ≈ 33ms. Covers AddTransition's midpoint rounding without false positives. */
        private val EPSILON: Duration = 34.milliseconds
    }
}
