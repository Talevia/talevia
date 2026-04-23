package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.diff.capTimelineDiff
import io.talevia.core.tool.builtin.project.diff.computeTimelineDiffRaw
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
 * Diff two project payloads — a snapshot vs another snapshot, a snapshot vs current state,
 * or a fork vs its parent. This is VISION §3.4's "可 diff" property: the agent can answer
 * "what actually changed between v1 and v2?" without dumping both Projects and asking the
 * model to spot the delta itself.
 *
 * `from` and `to` are each `(projectId, snapshotId?)`. A null snapshotId means "current
 * state of that project". The default cross-pair compares two snapshots within the same
 * project; `toProjectId` opens the cross-project case (e.g. fork vs parent).
 *
 * Three diff sections, scoped to the Project fields that move under user/agent edits:
 *
 *  - **Timeline**: tracks added/removed; clips added/removed/changed (matched by ClipId so
 *    a moved or re-asseted clip shows up as `changed`, not as remove+add).
 *  - **Source**: nodes added/removed/changed (changed = same id, different `contentHash`).
 *  - **Lockfile**: entry hashes added/removed (set-diff over `inputHash`). `addedToolIds`
 *    bucket-counts so the agent can say "this fork ran 3 generate_image and 1 tts".
 *
 * Per-section detail lists are capped at [MAX_DETAIL] items so a wholesale rewrite
 * doesn't blow the response into thousands of tokens; the totals in [Summary] are
 * always exact.
 *
 * Read-only — permission `project.read`.
 */
class DiffProjectsTool(
    private val projects: ProjectStore,
) : Tool<DiffProjectsTool.Input, DiffProjectsTool.Output> {

    @Serializable data class Input(
        val fromProjectId: String,
        /** Null → diff against `from` project's current state. */
        val fromSnapshotId: String? = null,
        /** Null → same project as `fromProjectId`. */
        val toProjectId: String? = null,
        /** Null → diff against `to` project's current state. */
        val toSnapshotId: String? = null,
    )

    @Serializable data class TrackRef(val trackId: String, val kind: String)

    @Serializable data class ClipRef(val clipId: String, val trackId: String, val kind: String)

    @Serializable data class ClipChange(
        val clipId: String,
        val trackId: String,
        val changedFields: List<String>,
    )

    @Serializable data class TimelineDiff(
        val tracksAdded: List<TrackRef> = emptyList(),
        val tracksRemoved: List<TrackRef> = emptyList(),
        val clipsAdded: List<ClipRef> = emptyList(),
        val clipsRemoved: List<ClipRef> = emptyList(),
        val clipsChanged: List<ClipChange> = emptyList(),
    )

    @Serializable data class SourceNodeRef(val nodeId: String, val kind: String)

    @Serializable data class SourceDiff(
        val nodesAdded: List<SourceNodeRef> = emptyList(),
        val nodesRemoved: List<SourceNodeRef> = emptyList(),
        val nodesChanged: List<SourceNodeRef> = emptyList(),
    )

    @Serializable data class LockfileDiff(
        val entriesAdded: Int = 0,
        val entriesRemoved: Int = 0,
        /** toolId → count among entries added in `to` but not in `from`. */
        val addedToolIds: Map<String, Int> = emptyMap(),
    )

    @Serializable data class Summary(
        val identical: Boolean,
        val totalTimelineChanges: Int,
        val totalSourceChanges: Int,
        val totalLockfileChanges: Int,
    )

    @Serializable data class Output(
        val fromLabel: String,
        val toLabel: String,
        val summary: Summary,
        val timeline: TimelineDiff,
        val source: SourceDiff,
        val lockfile: LockfileDiff,
    )

    override val id: String = "diff_projects"
    override val helpText: String =
        "Compare two project payloads — a snapshot, the current state, or a fork — and " +
            "report what changed across timeline, source DAG, and lockfile. Use this to answer " +
            "'what's different between v1 and v2?' or 'what did this fork add over its parent?' " +
            "without dumping both projects. Detail lists are capped; counts are always exact."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("fromProjectId") { put("type", "string") }
            putJsonObject("fromSnapshotId") {
                put("type", "string")
                put("description", "Optional — omit to diff against the project's current state.")
            }
            putJsonObject("toProjectId") {
                put("type", "string")
                put("description", "Optional — defaults to fromProjectId for same-project diffs.")
            }
            putJsonObject("toSnapshotId") {
                put("type", "string")
                put("description", "Optional — omit to diff against the to-project's current state.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("fromProjectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val (fromProject, fromLabel) = resolve(input.fromProjectId, input.fromSnapshotId, "from")
        val toProjectId = input.toProjectId ?: input.fromProjectId
        val (toProject, toLabel) = resolve(toProjectId, input.toSnapshotId, "to")

        val timeline = diffTimeline(fromProject, toProject)
        val source = diffSource(fromProject, toProject)
        val lockfile = diffLockfile(fromProject, toProject)

        val totalTimeline = timeline.tracksAdded.size + timeline.tracksRemoved.size +
            timeline.clipsAdded.size + timeline.clipsRemoved.size + timeline.clipsChanged.size
        val totalSource = source.nodesAdded.size + source.nodesRemoved.size + source.nodesChanged.size
        val totalLockfile = lockfile.entriesAdded + lockfile.entriesRemoved
        val identical = totalTimeline == 0 && totalSource == 0 && totalLockfile == 0

        val summary = Summary(
            identical = identical,
            totalTimelineChanges = totalTimeline,
            totalSourceChanges = totalSource,
            totalLockfileChanges = totalLockfile,
        )
        val out = Output(
            fromLabel = fromLabel,
            toLabel = toLabel,
            summary = summary,
            timeline = timeline,
            source = source,
            lockfile = lockfile,
        )

        val outputForLlm = if (identical) {
            "$fromLabel and $toLabel are identical (no timeline/source/lockfile changes)."
        } else {
            buildString {
                append("$fromLabel → $toLabel: ")
                val parts = mutableListOf<String>()
                if (totalTimeline > 0) parts += "timeline ${totalTimeline}Δ " +
                    "(+${timeline.clipsAdded.size}clip / -${timeline.clipsRemoved.size}clip / " +
                    "~${timeline.clipsChanged.size}clip)"
                if (totalSource > 0) parts += "source ${totalSource}Δ " +
                    "(+${source.nodesAdded.size} / -${source.nodesRemoved.size} / ~${source.nodesChanged.size})"
                if (totalLockfile > 0) parts += "lockfile +${lockfile.entriesAdded}/-${lockfile.entriesRemoved} entries"
                append(parts.joinToString("; "))
            }
        }
        return ToolResult(
            title = "diff projects",
            outputForLlm = outputForLlm,
            data = out,
        )
    }

    private suspend fun resolve(projectId: String, snapshotId: String?, side: String): Pair<Project, String> {
        val project = projects.get(ProjectId(projectId))
            ?: error("Project $projectId not found ($side side)")
        if (snapshotId == null) {
            return project to "$projectId @current"
        }
        val snap = project.snapshots.firstOrNull { it.id == ProjectSnapshotId(snapshotId) }
            ?: error("Snapshot $snapshotId not found on project $projectId ($side side)")
        return snap.project to "$projectId @${snap.label}"
    }

    private fun diffTimeline(from: Project, to: Project): TimelineDiff {
        val raw = computeTimelineDiffRaw(from, to)
        return TimelineDiff(
            tracksAdded = raw.tracksAdded.map { TrackRef(it.trackId, it.kind) }.capTimelineDiff(MAX_DETAIL),
            tracksRemoved = raw.tracksRemoved.map { TrackRef(it.trackId, it.kind) }.capTimelineDiff(MAX_DETAIL),
            clipsAdded = raw.clipsAdded.map { ClipRef(it.clipId, it.trackId, it.kind) }.capTimelineDiff(MAX_DETAIL),
            clipsRemoved = raw.clipsRemoved.map { ClipRef(it.clipId, it.trackId, it.kind) }.capTimelineDiff(MAX_DETAIL),
            clipsChanged = raw.clipsChanged.map { ClipChange(it.clipId, it.trackId, it.changedFields) }.capTimelineDiff(MAX_DETAIL),
        )
    }

    private fun diffSource(from: Project, to: Project): SourceDiff {
        val fromNodes = from.source.nodes.associateBy { it.id.value }
        val toNodes = to.source.nodes.associateBy { it.id.value }

        val added = (toNodes.keys - fromNodes.keys).map { id ->
            SourceNodeRef(id, toNodes.getValue(id).kind)
        }
        val removed = (fromNodes.keys - toNodes.keys).map { id ->
            SourceNodeRef(id, fromNodes.getValue(id).kind)
        }
        val changed = (fromNodes.keys intersect toNodes.keys).mapNotNull { id ->
            val f = fromNodes.getValue(id)
            val t = toNodes.getValue(id)
            if (f.contentHash == t.contentHash) null
            else SourceNodeRef(id, t.kind)
        }
        return SourceDiff(
            nodesAdded = added.capTimelineDiff(MAX_DETAIL),
            nodesRemoved = removed.capTimelineDiff(MAX_DETAIL),
            nodesChanged = changed.capTimelineDiff(MAX_DETAIL),
        )
    }

    private fun diffLockfile(from: Project, to: Project): LockfileDiff {
        val fromHashes = from.lockfile.entries.map { it.inputHash }.toSet()
        val toEntries = to.lockfile.entries
        val addedEntries = toEntries.filter { it.inputHash !in fromHashes }
        val toHashes = toEntries.map { it.inputHash }.toSet()
        val removedCount = from.lockfile.entries.count { it.inputHash !in toHashes }
        val byTool = addedEntries.groupingBy { it.toolId }.eachCount()
        return LockfileDiff(
            entriesAdded = addedEntries.size,
            entriesRemoved = removedCount,
            addedToolIds = byTool,
        )
    }

    companion object {
        /** Per-list cap on detail items. Counts in [Summary] remain exact. */
        private const val MAX_DETAIL = 50
    }
}
