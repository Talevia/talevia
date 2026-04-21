package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Serialize a Project into a portable JSON envelope — the project-level
 * counterpart of [ExportSourceNodeTool].
 *
 * VISION §3.4 states that Project / Timeline should behave like a
 * codebase: readable, diffable, versionable, composable. The
 * composability leg already had an intra-instance path
 * (`fork_project`), but cross-instance portability was missing —
 * users who wanted to:
 *   - back up a project to disk,
 *   - share with a collaborator on a different Talevia instance,
 *   - check the project into version control alongside source assets,
 *   - ship a pre-baked project as a reusable template,
 * had no tool to produce the envelope.
 *
 * The envelope captures the full [Project] payload (timeline, source,
 * outputProfile, assets catalog, lockfile, renderCache, snapshots)
 * through the canonical `Project.serializer()` + `JsonConfig.default`,
 * so the wire shape matches what the store persists. `formatVersion`
 * tags the schema; the inverse `import_project_from_json` rejects
 * unknown versions loudly.
 *
 * Sessions are intentionally **not** included — they reference
 * projects, not vice versa, and a session's meaning is tied to the
 * specific Agent conversation that produced it. A cross-instance
 * project import ending up with orphan sessions would confuse every
 * session-lane tool; keeping the envelope project-scoped keeps the
 * contract crisp.
 *
 * Read-only, `project.read`.
 */
class ExportProjectTool(
    private val projects: ProjectStore,
) : Tool<ExportProjectTool.Input, ExportProjectTool.Output> {

    private val json: Json get() = JsonConfig.default

    @Serializable data class Input(
        val projectId: String,
        /** Pretty-print the envelope? Default false (compact wire shape). */
        val prettyPrint: Boolean = false,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        val formatVersion: String,
        val assetCount: Int,
        val sourceNodeCount: Int,
        val trackCount: Int,
        val clipCount: Int,
        val lockfileEntryCount: Int,
        val snapshotCount: Int,
        /** The serialized envelope string, ready for `write_file` or
         *  `import_project_from_json`. */
        val envelope: String,
    )

    override val id: String = "export_project"
    override val helpText: String =
        "Serialize a project (timeline + source + assets + lockfile + snapshots + renderCache) into " +
            "a portable JSON envelope. Use for backup / cross-instance share / version control. Round-" +
            "trips via import_project_from_json; unknown formatVersions rejected. Sessions are NOT " +
            "included (they reference projects, not vice versa)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("prettyPrint") {
                put("type", "boolean")
                put("description", "Pretty-print the envelope. Default false (compact).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val title = projects.summary(pid)?.title ?: input.projectId

        val envelope = ProjectEnvelope(
            formatVersion = FORMAT_VERSION,
            title = title,
            project = project,
        )
        val jsonInstance = if (input.prettyPrint) Json(from = json) { prettyPrint = true } else json
        val serialized = jsonInstance.encodeToString(ProjectEnvelope.serializer(), envelope)

        val assetCount = project.assets.size
        val sourceNodeCount = project.source.nodes.size
        val trackCount = project.timeline.tracks.size
        val clipCount = project.timeline.tracks.sumOf { it.clips.size }
        val lockfileEntryCount = project.lockfile.entries.size
        val snapshotCount = project.snapshots.size

        return ToolResult(
            title = "export project ${project.id.value}",
            outputForLlm = "Exported ${project.id.value} '$title' as $FORMAT_VERSION " +
                "($trackCount track(s), $clipCount clip(s), $assetCount asset(s), " +
                "$sourceNodeCount source node(s), $lockfileEntryCount lockfile entry(ies), " +
                "$snapshotCount snapshot(s); ${serialized.length} bytes).",
            data = Output(
                projectId = project.id.value,
                title = title,
                formatVersion = FORMAT_VERSION,
                assetCount = assetCount,
                sourceNodeCount = sourceNodeCount,
                trackCount = trackCount,
                clipCount = clipCount,
                lockfileEntryCount = lockfileEntryCount,
                snapshotCount = snapshotCount,
                envelope = serialized,
            ),
        )
    }

    companion object {
        const val FORMAT_VERSION: String = "talevia-project-export-v1"
    }
}

/**
 * Wire format for project export. `title` is duplicated here (the Project
 * itself doesn't carry a title; the store does via `ProjectSummary`) so
 * the round-trip preserves the display name.
 */
@Serializable
data class ProjectEnvelope(
    val formatVersion: String,
    val title: String,
    val project: Project,
)
