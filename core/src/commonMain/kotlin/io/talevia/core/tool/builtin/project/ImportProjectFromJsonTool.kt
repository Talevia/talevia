package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.computeProjectValidationIssues
import io.talevia.core.domain.renderProjectValidationIssues
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
import okio.Path.Companion.toPath

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
 *
 * **Integrity validation.** Before the project is upserted the envelope
 * is run through the same structural checks as project_query(select=validation)
 * (clip → asset / source-binding references, source DAG parent integrity,
 * audio envelope ranges, etc.). Any `error`-severity issue aborts the
 * import with a rendered summary rather than upserting a project the
 * rest of the stack (`project_query(select=stale_clips)`, export, renderers) will trip
 * over later. Warnings never block — they're reported via the Output
 * counters. Pass `force = true` to bypass the gate (for import-to-fix
 * workflows); counters are still populated.
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
        /**
         * Skip the integrity gate. When `true`, a project with structural
         * `error`-severity issues is still upserted; the issues are reported
         * in the Output so the caller can fix them post-import. Default
         * `false` — the gate is on.
         */
        val force: Boolean = false,
        /**
         * Optional filesystem path for the imported bundle. See
         * [ProjectActionTool.Input.path] (action="create") for semantics.
         */
        val path: String? = null,
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
        /** Total integrity issues found pre-upsert. */
        val validationIssueCount: Int = 0,
        /** Count of `"error"`-severity issues. A non-zero value with
         *  `force=false` means the tool failed loudly instead of upserting. */
        val validationErrorCount: Int = 0,
        /** Count of `"warn"`-severity issues. Never blocks. */
        val validationWarnCount: Int = 0,
        /** First N issue codes, for caller triage without needing to re-run
         *  `validate_project`. Empty when the envelope is clean. */
        val validationIssueCodes: List<String> = emptyList(),
    )

    override val id: String = "import_project_from_json"
    override val helpText: String =
        "Ingest a project envelope produced by export_project. Lands at the envelope's original " +
            "projectId unless you pass newProjectId to rename. Collision on the target id fails " +
            "loudly. Unknown formatVersions rejected. Runs validate_project's integrity checks " +
            "pre-upsert; structural errors (dangling asset / source-binding refs, source DAG " +
            "parent-dangling / cycles, etc.) abort the import unless `force=true` is set. Note: " +
            "asset bytes are NOT bundled — the importing instance needs access to the underlying " +
            "media paths the assets reference."
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
            putJsonObject("force") {
                put("type", "boolean")
                put(
                    "description",
                    "When true, skip the integrity gate: structural errors no longer abort the import. " +
                        "Use for import-to-fix workflows. Default false.",
                )
            }
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description",
                    "Optional absolute filesystem path for the imported bundle. Defaults to the " +
                        "store's default-projects-home. The directory must not already contain a " +
                        "talevia.json.",
                )
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

        // Integrity check BEFORE upsert. Import is a trust boundary: the
        // envelope may come from a different instance or hand-edited file,
        // so structural errors (dangling refs, source-DAG corruption) must
        // not leak into the store where the rest of the stack assumes them
        // absent. `force=true` bypasses the gate for import-to-fix flows.
        val issues = computeProjectValidationIssues(rehomed)
        val errorIssues = issues.filter { it.severity == "error" }
        val warnIssues = issues.filter { it.severity == "warn" }
        val issueCodes = issues.take(10).map { it.code }
        if (errorIssues.isNotEmpty() && !input.force) {
            error(
                "Envelope failed integrity checks (${errorIssues.size} error(s), " +
                    "${warnIssues.size} warning(s)); refusing to import. Fix the envelope, pass " +
                    "force=true to import-then-fix, or re-export from a clean project.\n" +
                    renderProjectValidationIssues(issues),
            )
        }

        val targetIdAfterPersist: ProjectId = if (input.path != null && input.path.isNotBlank()) {
            // createAt mints a fresh id; layer the imported body on via mutate
            // so the bundle reflects the envelope's payload exactly.
            val created = projects.createAt(
                path = input.path.toPath(),
                title = targetTitle,
                timeline = rehomed.timeline,
                outputProfile = rehomed.outputProfile,
            )
            projects.mutate(created.id) { rehomed.copy(id = created.id) }
            created.id
        } else {
            projects.upsert(targetTitle, rehomed)
            targetId
        }

        val sourceNodeCount = rehomed.source.nodes.size
        val trackCount = rehomed.timeline.tracks.size
        val clipCount = rehomed.timeline.tracks.sumOf { it.clips.size }
        val assetCount = rehomed.assets.size
        val lockfileEntryCount = rehomed.lockfile.entries.size
        val snapshotCount = rehomed.snapshots.size

        val validationNote = when {
            issues.isEmpty() -> ""
            errorIssues.isEmpty() -> " — ${warnIssues.size} warning(s) recorded on the project"
            else -> " — ${errorIssues.size} error(s) / ${warnIssues.size} warning(s) carried in under force=true"
        }

        return ToolResult(
            title = "import project ${targetIdAfterPersist.value}",
            outputForLlm = "Ingested ${decoded.formatVersion} envelope as ${targetIdAfterPersist.value} " +
                "'$targetTitle' ($trackCount track(s), $clipCount clip(s), $assetCount asset(s), " +
                "$sourceNodeCount source node(s), $lockfileEntryCount lockfile entry(ies), " +
                "$snapshotCount snapshot(s))$validationNote. Asset bytes not bundled — ensure " +
                "target media paths resolve.",
            data = Output(
                projectId = targetIdAfterPersist.value,
                title = targetTitle,
                formatVersion = decoded.formatVersion,
                sourceNodeCount = sourceNodeCount,
                trackCount = trackCount,
                clipCount = clipCount,
                assetCount = assetCount,
                lockfileEntryCount = lockfileEntryCount,
                snapshotCount = snapshotCount,
                validationIssueCount = issues.size,
                validationErrorCount = errorIssues.size,
                validationWarnCount = warnIssues.size,
                validationIssueCodes = issueCodes,
            ),
        )
    }
}
