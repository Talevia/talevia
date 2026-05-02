package io.talevia.core.tool.builtin.project.query

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.incrementalPlan
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Single-row `select=incremental_plan` payload — the 3-bucket
 * pre-export answer to "if I edit these source nodes, what work
 * does the next export need?". Mirrors
 * [io.talevia.core.domain.IncrementalPlan] but with stringified
 * [io.talevia.core.ClipId] values for JSON consumers.
 *
 * Buckets are pairwise disjoint and their union is the set of clips
 * reachable from the queried `sourceNodeIds` via the source DAG
 * closure. [workCount] = `reAigc + onlyRender` is a convenience for
 * the LLM's UX prose ("3 clips need re-rendering").
 *
 * Empty `sourceNodeIds` (or no clips bound to any of them) → all
 * three buckets empty (`workCount=0`).
 */
@Serializable
data class IncrementalPlanRow(
    val reAigc: List<String>,
    val onlyRender: List<String>,
    val unchanged: List<String>,
    val workCount: Int,
)

/**
 * `select=incremental_plan` — wraps [Project.incrementalPlan] for
 * agent-side blast-radius preview before a source edit. M5 §3.2
 * criterion #1 capstone, deferred from cycle 8 (b2f1bef6) and now
 * exposed through the query dispatcher.
 *
 * Returns a single-element `rows` array (the plan itself); `total`
 * and `returned` are both 1. Limit/offset don't apply — the plan
 * is one indivisible answer.
 */
internal fun runIncrementalPlanQuery(
    project: Project,
    input: ProjectQueryTool.Input,
): ToolResult<ProjectQueryTool.Output> {
    val engineId = input.engineId ?: DEFAULT_PER_CLIP_ENGINE_ID
    val output = outputSpecFromProfile(project.outputProfile)
    val changedSources = (input.sourceNodeIds ?: emptyList()).map { SourceNodeId(it) }.toSet()
    val plan = project.incrementalPlan(changedSources, output, engineId)

    val row = IncrementalPlanRow(
        reAigc = plan.reAigc.map { it.value },
        onlyRender = plan.onlyRender.map { it.value },
        unchanged = plan.unchanged.map { it.value },
        workCount = plan.workCount,
    )
    val jsonRows = encodeRows(ListSerializer(IncrementalPlanRow.serializer()), listOf(row))

    val summary = if (plan.isEmpty) {
        "No work — sourceNodeIds=${changedSources.size} reach 0 bound clips (or all unchanged)."
    } else {
        "engine=$engineId | reAigc=${plan.reAigc.size} onlyRender=${plan.onlyRender.size} " +
            "unchanged=${plan.unchanged.size} | total work units=${plan.workCount}"
    }

    return ToolResult(
        title = "project_query incremental_plan (${plan.workCount} work units)",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
            total = 1,
            returned = 1,
            rows = jsonRows,
        ),
    )
}
