package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Ingest a project envelope produced by [ExportProjectTool]. Closes
 * the cross-instance portability loop.
 *
 * By default, the imported project lands at the envelope's original
 * `projectId`. Collision (a project with that id already exists) fails
 * loudly — pass `newProjectId` to rename. The imported project retains
 * the serialized timeline, source DAG, lockfile, snapshots, and asset
 * catalog; the asset *bytes* are NOT included in the envelope (it's a
 * metadata-only format — the caller is responsible for ensuring the
 * media paths the assets reference are still resolvable on the target
 * instance).
 *
 * Unknown `formatVersion` values are rejected loudly. Invalid JSON
 * produces a structured error with the underlying parser message.
 */
class ImportProjectFromJsonTool(
    private val projects: ProjectStore,
) : Tool<ImportProjectFromJsonTool.Input, ImportProjectFromJsonTool.Output> {

    private val json get() = JsonConfig.default

    @Serializable data class Input(
        /** The exact string `export_project` produced. */
        val envelope: String,
        /** Optional rename. If null, keeps the envelope's original projectId. */
        val newProjectId: String? = null,
        /** Optional new title. Defaults to the envelope's title. */
        val newTitle: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        val formatVersion: String,
        val sourceNodeCount: Int,
        val trackCount: Int,
        val clipCount: Int,
        val assetCount: Int,
        val lockfileEntryCount: Int,
        val snapshotCount: Int,
    )

    override val id: String = "import_project_from_json"
    override val helpText: String =
        "Ingest a project envelope produced by export_project. Lands at the envelope's original " +
            "projectId unless you pass newProjectId to rename. Collision on the target id fails " +
            "loudly. Unknown formatVersions rejected. Note: asset bytes are NOT bundled — the " +
            "importing instance needs access to the underlying media paths the assets reference."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("envelope") {
                put("type", "string")
                put("description", "JSON envelope from export_project (data.envelope output).")
            }
            putJsonObject("newProjectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional rename. Use when the envelope's original id would collide on the target.",
                )
            }
            putJsonObject("newTitle") {
                put("type", "string")
                put("description", "Optional new title. Defaults to the envelope's title.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("envelope"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val decoded: ProjectEnvelope = try {
            json.decodeFromString(ProjectEnvelope.serializer(), input.envelope)
        } catch (e: SerializationException) {
            error("Envelope is not valid JSON for the project-export schema: ${e.message}")
        }
        require(decoded.formatVersion == ExportProjectTool.FORMAT_VERSION) {
            "Envelope formatVersion='${decoded.formatVersion}' is not understood by this importer " +
                "(expected ${ExportProjectTool.FORMAT_VERSION}). Re-export from a compatible Talevia build."
        }

        val targetId = input.newProjectId?.takeIf { it.isNotBlank() }?.let { ProjectId(it) }
            ?: decoded.project.id
        require(projects.get(targetId) == null) {
            "Target project ${targetId.value} already exists. Pass newProjectId to rename on import."
        }

        val targetTitle = input.newTitle?.takeIf { it.isNotBlank() } ?: decoded.title
        val rehomed = if (targetId == decoded.project.id) {
            decoded.project
        } else {
            decoded.project.copy(id = targetId)
        }
        projects.upsert(targetTitle, rehomed)

        val sourceNodeCount = rehomed.source.nodes.size
        val trackCount = rehomed.timeline.tracks.size
        val clipCount = rehomed.timeline.tracks.sumOf { it.clips.size }
        val assetCount = rehomed.assets.size
        val lockfileEntryCount = rehomed.lockfile.entries.size
        val snapshotCount = rehomed.snapshots.size

        return ToolResult(
            title = "import project ${targetId.value}",
            outputForLlm = "Ingested ${decoded.formatVersion} envelope as ${targetId.value} " +
                "'$targetTitle' ($trackCount track(s), $clipCount clip(s), $assetCount asset(s), " +
                "$sourceNodeCount source node(s), $lockfileEntryCount lockfile entry(ies), " +
                "$snapshotCount snapshot(s)). Asset bytes not bundled — ensure target media paths resolve.",
            data = Output(
                projectId = targetId.value,
                title = targetTitle,
                formatVersion = decoded.formatVersion,
                sourceNodeCount = sourceNodeCount,
                trackCount = trackCount,
                clipCount = clipCount,
                assetCount = assetCount,
                lockfileEntryCount = lockfileEntryCount,
                snapshotCount = snapshotCount,
            ),
        )
    }
}
