package io.talevia.core.tool.builtin.project

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import kotlin.time.Duration

/**
 * Per-axis structural lint checks for [ValidateProjectTool].
 *
 * Three axes today: timeline duration consistency, per-clip integrity
 * (asset / source-binding / volume / fade), and source-DAG integrity
 * (dangling parents, parent cycles). Extracted from
 * `ValidateProjectTool.kt` so the dispatcher class stays focused on
 * (input → checks → output) plumbing — each axis check is a pure
 * function over [Project] returning a list of [ValidateProjectTool.Issue].
 *
 * Composition by [ValidateProjectTool.Companion.computeIssues] (and
 * `ImportProjectFromJsonTool` at envelope-import time) — both call
 * sites concatenate the per-axis lists so an envelope import that
 * passes the linter must also pass on the target project.
 *
 * Behaviour is byte-identical to the previous file-private helpers.
 */

internal fun timelineDurationIssues(project: Project): List<ValidateProjectTool.Issue> {
    val actualMax = project.timeline.tracks
        .flatMap { it.clips }
        .maxOfOrNull { it.timeRange.end }
        ?: Duration.ZERO
    if (project.timeline.duration < actualMax) {
        return listOf(
            ValidateProjectTool.Issue(
                severity = "warn",
                code = "duration-mismatch",
                message = "timeline.duration (${project.timeline.duration.secondsString()}s) " +
                    "is less than the latest clip end (${actualMax.secondsString()}s); " +
                    "engines will truncate output.",
            ),
        )
    }
    return emptyList()
}

internal fun clipIssues(
    project: Project,
    track: Track,
    clip: Clip,
): List<ValidateProjectTool.Issue> {
    val result = mutableListOf<ValidateProjectTool.Issue>()
    val trackIdValue = track.id.value
    val clipIdValue = clip.id.value

    if (clip.timeRange.duration <= Duration.ZERO) {
        result += ValidateProjectTool.Issue(
            severity = "error",
            code = "non-positive-duration",
            message = "clip duration must be > 0 (got ${clip.timeRange.duration.secondsString()}s)",
            trackId = trackIdValue,
            clipId = clipIdValue,
        )
    }

    val assetId = when (clip) {
        is Clip.Video -> clip.assetId.value
        is Clip.Audio -> clip.assetId.value
        is Clip.Text -> null
    }
    if (assetId != null && project.assets.none { it.id.value == assetId }) {
        result += ValidateProjectTool.Issue(
            severity = "error",
            code = "dangling-asset",
            message = "clip references assetId '$assetId' which is not in project.assets",
            trackId = trackIdValue,
            clipId = clipIdValue,
        )
    }

    for (nodeId in clip.sourceBinding) {
        if (nodeId !in project.source.byId) {
            result += ValidateProjectTool.Issue(
                severity = "error",
                code = "dangling-source-binding",
                message = "clip binds source node '${nodeId.value}' which is not in project.source",
                trackId = trackIdValue,
                clipId = clipIdValue,
            )
        }
    }

    if (clip is Clip.Audio) {
        if (clip.volume < 0f || clip.volume > 4f) {
            result += ValidateProjectTool.Issue(
                severity = "error",
                code = "volume-range",
                message = "audio clip volume ${clip.volume} is outside the [0, 4] range",
                trackId = trackIdValue,
                clipId = clipIdValue,
            )
        }
        if (clip.fadeInSeconds < 0f || clip.fadeOutSeconds < 0f) {
            result += ValidateProjectTool.Issue(
                severity = "error",
                code = "fade-negative",
                message = "audio clip has negative fade (in=${clip.fadeInSeconds}s, out=${clip.fadeOutSeconds}s)",
                trackId = trackIdValue,
                clipId = clipIdValue,
            )
        }
        val durSec = clip.timeRange.duration.inWholeMilliseconds / 1000.0
        val fadeTotal = (clip.fadeInSeconds + clip.fadeOutSeconds).toDouble()
        if (fadeTotal > durSec) {
            result += ValidateProjectTool.Issue(
                severity = "error",
                code = "fade-overlap",
                message = "audio fade-in + fade-out (${fadeTotal}s) exceeds clip duration (${durSec}s)",
                trackId = trackIdValue,
                clipId = clipIdValue,
            )
        }
    }

    return result
}

private fun Duration.secondsString(): String {
    val millis = inWholeMilliseconds
    val whole = millis / 1000
    val frac = (millis % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
}

/**
 * Source DAG integrity — detect two structural faults neither of which
 * the source-mutation helpers (addNode / replaceNode) guard against
 * today:
 *
 *  1. **Dangling parent**: a `SourceRef` whose nodeId is not present in
 *     `project.source.byId`. Typically arises from `source_node_action(action=remove)`
 *     on a referenced node (the kdoc explicitly says it does not
 *     cascade). Breaks staleness propagation silently — `Source.stale`
 *     walks parents and simply skips missing ids, so an edit upstream of
 *     the dangling edge never marks the orphaned descendant stale.
 *
 *  2. **Parent cycle**: a→b→c→a in the `parents` relation. Breaks DFS-
 *     based traversals (`source_query(select=dag_summary).computeMaxDepth` has a
 *     cycle-guard, but other walkers — including the export-time
 *     topological ordering in `ExportSourceNodeTool.topoCollect` — assume
 *     an acyclic DAG). Also implies the hash-driven dedup logic in
 *     content-addressed cases could behave in surprising ways.
 *
 * Both are reported as errors rather than warnings: they silently
 * corrupt downstream behaviour (stale propagation, topological export,
 * staleness detection), and `passed=false` should block an export until
 * the agent fixes the DAG.
 */
internal fun sourceDagIssues(project: Project): List<ValidateProjectTool.Issue> {
    val nodes = project.source.nodes
    if (nodes.isEmpty()) return emptyList()
    val byId = project.source.byId
    val issues = mutableListOf<ValidateProjectTool.Issue>()

    // Tier 1 — dangling parents, reported per-edge.
    for (node in nodes) {
        for (parent in node.parents) {
            if (parent.nodeId !in byId) {
                issues += ValidateProjectTool.Issue(
                    severity = "error",
                    code = "source-parent-dangling",
                    message = "source node '${node.id.value}' references missing parent " +
                        "'${parent.nodeId.value}' (call set_source_node_parents / " +
                        "source_node_action(action=remove) to fix).",
                )
            }
        }
    }

    // Tier 2 — cycle detection via iterative DFS over `parents`.
    // White = unvisited, Grey = on current DFS stack, Black = finished.
    // A back-edge to Grey is a cycle. One issue per detected cycle
    // (we break once a cycle is found from a given start node).
    val white = nodes.map { it.id }.toMutableSet()
    val grey = mutableSetOf<SourceNodeId>()
    val black = mutableSetOf<SourceNodeId>()
    val reported = mutableSetOf<Set<SourceNodeId>>()

    fun visit(startId: SourceNodeId) {
        // Iterative DFS with an explicit stack so we can carry the path
        // and emit the cycle nodes without deep recursion.
        val stack = ArrayDeque<Pair<SourceNodeId, Iterator<SourceNodeId>>>()
        val path = ArrayDeque<SourceNodeId>()

        fun push(id: SourceNodeId) {
            white.remove(id)
            grey += id
            path.addLast(id)
            val parents = byId[id]?.parents.orEmpty().map { it.nodeId }.iterator()
            stack.addLast(id to parents)
        }

        push(startId)
        while (stack.isNotEmpty()) {
            val (currentId, iter) = stack.last()
            if (!iter.hasNext()) {
                stack.removeLast()
                grey.remove(currentId)
                black += currentId
                path.removeLast()
                continue
            }
            val next = iter.next()
            if (next !in byId) continue // dangling; already reported above
            if (next in black) continue
            if (next in grey) {
                // Found a cycle: the path from `next` to `currentId` +
                // back to `next` is the cycle. Record the set of ids to
                // avoid re-reporting the same cycle from another start.
                val cycleStart = path.indexOf(next)
                val cycleNodes = if (cycleStart >= 0) {
                    path.toList().subList(cycleStart, path.size).toSet()
                } else {
                    setOf(next, currentId)
                }
                if (reported.add(cycleNodes)) {
                    val rendered = cycleNodes.joinToString(" → ") { it.value } +
                        " → ${next.value}"
                    issues += ValidateProjectTool.Issue(
                        severity = "error",
                        code = "source-parent-cycle",
                        message = "source DAG has a parent-cycle involving: $rendered. Use " +
                            "set_source_node_parents to break the cycle.",
                    )
                }
                continue
            }
            push(next)
        }
    }

    while (white.isNotEmpty()) {
        val start = white.first()
        visit(start)
    }

    return issues
}
