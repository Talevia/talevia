package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
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

/**
 * Structural lint for a project. The agent can accumulate state across many
 * edits and — especially during autonomous multi-step runs — leave the
 * project in a shape that will bite at export time: a clip pointing at an
 * imported-then-deleted asset, a source binding whose node id no longer
 * exists after `source_node_action(action=remove)`, an audio clip whose `volume` was
 * clamped to 5.0 by a hand-authored snapshot, a fade-in/out pair that
 * exceeds the clip duration.
 *
 * Without a tool, the only way to catch these was to export and watch the
 * engine fail (or, worse, silently render a garbled frame). With this tool
 * the agent can run a cheap in-memory pass before every export, report
 * each issue with `severity / code / trackId / clipId`, and fix what it can
 * proactively.
 *
 * Intentionally narrow: structural invariants only. Does NOT cover:
 * - Staleness (lockfile / contentHash drift) — that's `find_stale_clips`.
 * - Render-cache health — exports already re-check.
 * - Semantic "is this a good edit" judgment — out of scope for a linter.
 *
 * The result is always returned (the tool never fails loudly for a
 * project-state problem); callers branch on `passed = errorCount == 0`.
 * A not-found project id still fails loudly because that's a caller bug.
 */
class ValidateProjectTool(
    private val projects: ProjectStore,
) : Tool<ValidateProjectTool.Input, ValidateProjectTool.Output> {

    @Serializable data class Input(
        val projectId: String,
    )

    @Serializable data class Issue(
        /** `"error"` | `"warn"` — `error` blocks export-readiness. */
        val severity: String,
        /**
         * Stable machine code so callers can switch on it (e.g. an autofix path
         * could target `"dangling-asset"` specifically). See the tool KDoc for
         * the current code vocabulary.
         */
        val code: String,
        val message: String,
        val trackId: String? = null,
        val clipId: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val issueCount: Int,
        val errorCount: Int,
        val warnCount: Int,
        val issues: List<Issue>,
        /** `true` iff `errorCount == 0`. Warnings do not block. */
        val passed: Boolean,
    )

    override val id: String = "validate_project"
    override val helpText: String =
        "Lint a project for structural invariants before export: dangling asset refs, " +
            "dangling source-node bindings, non-positive clip durations, audio volume / " +
            "fade envelope out of range, timeline duration out of sync with clips, and " +
            "source DAG integrity (parent refs pointing at missing nodes; parent cycles " +
            "that would break DFS traversal). Returns one row per issue; `passed=true` iff " +
            "no errors. Does NOT check content staleness — use `find_stale_clips` for that."
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

        val issues = computeIssues(project)
        val errorCount = issues.count { it.severity == "error" }
        val warnCount = issues.count { it.severity == "warn" }
        val out = Output(
            projectId = pid.value,
            issueCount = issues.size,
            errorCount = errorCount,
            warnCount = warnCount,
            issues = issues,
            passed = errorCount == 0,
        )
        return ToolResult(
            title = if (out.passed) "validate project: ok" else "validate project: $errorCount error(s)",
            outputForLlm = summarise(out),
            data = out,
        )
    }

    private fun summarise(out: Output): String {
        if (out.issues.isEmpty()) return "Project ${out.projectId} passed validation (0 issues)."
        val head = "Project ${out.projectId}: ${out.errorCount} error(s), ${out.warnCount} warning(s)."
        val body = out.issues.joinToString("\n") { issue ->
            val where = listOfNotNull(
                issue.trackId?.let { "track=$it" },
                issue.clipId?.let { "clip=$it" },
            ).joinToString(" ")
            val locator = if (where.isEmpty()) "" else " ($where)"
            "- [${issue.severity}] ${issue.code}$locator: ${issue.message}"
        }
        return "$head\n$body"
    }

    companion object {
        /**
         * Pure structural check over [project]. Shared by [ValidateProjectTool.execute]
         * (which has a stored project) and [ImportProjectFromJsonTool] (which has a
         * decoded-but-not-yet-upserted envelope). Extracted so the two code paths
         * cannot drift — an envelope that imports clean must also pass the linter on
         * the target, and vice versa.
         */
        fun computeIssues(project: Project): List<Issue> = buildList {
            addAll(timelineDurationIssues(project))
            for (track in project.timeline.tracks) {
                for (clip in track.clips) {
                    addAll(clipIssues(project, track, clip))
                }
            }
            addAll(sourceDagIssues(project))
        }

        /**
         * Render a short, human-readable summary of [issues] suitable for surfacing
         * in an `error { ... }` message at an import / ingest boundary. Caps at
         * [maxLines] issues and appends `... (N more)` for the rest.
         */
        fun renderIssues(issues: List<Issue>, maxLines: Int = 5): String {
            if (issues.isEmpty()) return ""
            val head = issues.take(maxLines).joinToString("\n") { issue ->
                val where = listOfNotNull(
                    issue.trackId?.let { "track=$it" },
                    issue.clipId?.let { "clip=$it" },
                ).joinToString(" ")
                val locator = if (where.isEmpty()) "" else " ($where)"
                "- [${issue.severity}] ${issue.code}$locator: ${issue.message}"
            }
            val extra = issues.size - maxLines
            return if (extra > 0) "$head\n… ($extra more)" else head
        }
    }
}

private fun timelineDurationIssues(project: Project): List<ValidateProjectTool.Issue> {
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

private fun clipIssues(
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
private fun sourceDagIssues(project: Project): List<ValidateProjectTool.Issue> {
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
