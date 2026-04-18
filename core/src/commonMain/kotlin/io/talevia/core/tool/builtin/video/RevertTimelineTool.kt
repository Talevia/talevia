package io.talevia.core.tool.builtin.video

import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
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
 * Roll the project's timeline back to an earlier [Part.TimelineSnapshot].
 *
 * Every mutating tool (add_clip, split_clip, apply_filter, add_subtitle,
 * add_transition, and this tool itself) emits a `TimelineSnapshot` part after
 * it commits and surfaces the new snapshot's id in its `outputForLlm` text.
 *
 * The revert tool accepts `snapshotPartId` explicitly — the LLM is expected to
 * look back at its own tool-result history and pass the id of the snapshot
 * corresponding to the state the user wants to restore. An explicit id keeps
 * the semantics unambiguous under repeated undos; the tool additionally emits a
 * fresh snapshot for the post-revert state so subsequent reverts have a
 * consistent handle to reference.
 */
class RevertTimelineTool(
    private val sessions: SessionStore,
    private val projects: ProjectStore,
) : Tool<RevertTimelineTool.Input, RevertTimelineTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val snapshotPartId: String,
    )

    @Serializable data class Output(
        val appliedSnapshotPartId: String,
        val newSnapshotPartId: String,
        val clipCount: Int,
        val trackCount: Int,
    )

    override val id = "revert_timeline"
    override val helpText =
        "Roll the project timeline back to a prior TimelineSnapshot. Pass the " +
            "snapshotPartId reported by an earlier mutating tool's result."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("snapshotPartId") {
                put("type", "string")
                put("description", "PartId of the target TimelineSnapshot (from a prior tool-result).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("snapshotPartId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val part = sessions.getPart(PartId(input.snapshotPartId))
            ?: error("Snapshot part ${input.snapshotPartId} not found")
        val target = part as? Part.TimelineSnapshot
            ?: error("Part ${input.snapshotPartId} is not a TimelineSnapshot (kind=${part::class.simpleName})")
        if (target.sessionId != ctx.sessionId) {
            error("Snapshot ${input.snapshotPartId} belongs to a different session")
        }

        val updated = projects.mutate(ProjectId(input.projectId)) { project ->
            project.copy(timeline = target.timeline)
        }

        val newSnapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val clipCount = updated.timeline.tracks.sumOf { it.clips.size }
        val trackCount = updated.timeline.tracks.size
        return ToolResult(
            title = "revert timeline",
            outputForLlm =
                "Reverted to snapshot ${target.id.value} ($clipCount clip(s), $trackCount track(s)). " +
                    "New timeline snapshot: ${newSnapshotId.value}",
            data = Output(
                appliedSnapshotPartId = target.id.value,
                newSnapshotPartId = newSnapshotId.value,
                clipCount = clipCount,
                trackCount = trackCount,
            ),
        )
    }
}
