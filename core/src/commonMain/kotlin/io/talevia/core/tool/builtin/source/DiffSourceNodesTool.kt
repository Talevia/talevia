package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Node-level delta between two source nodes — the sibling of [DiffProjectsTool] that
 * zooms in from whole-project diffs to a pair of specific nodes. Closes VISION §5.1
 * ("改一个 source 节点…这个关系是显式的吗？") by making node-level deltas legible to
 * the agent: kind change, contentHash change, per-field body deltas, and parent
 * set adds/removes.
 *
 * Two modes selected by input shape:
 *  - **Within-project**: omit `leftProjectId` / `rightProjectId` → both nodes are
 *    resolved from `projectId`. Useful for diffing a `set_character_ref` against
 *    a later `fork_source_node` of it.
 *  - **Cross-project**: set `leftProjectId` and/or `rightProjectId` → either override
 *    `projectId` for that side. Useful post-`fork_project` when comparing a
 *    character variant in the fork against its origin.
 *
 * Missing nodes are reported structurally (`leftExists` / `rightExists` / `bothExist`)
 * rather than throwing — the agent may be asking "did this node still exist after my
 * rename?" and a structured answer beats an exception. Missing **projects** still
 * fail loud: that's a caller bug, not a knowable outcome.
 *
 * Read-only — permission `project.read`.
 */
class DiffSourceNodesTool(
    private val projects: ProjectStore,
) : Tool<DiffSourceNodesTool.Input, DiffSourceNodesTool.Output> {

    @Serializable data class Input(
        /** Default project for both sides; overridden per-side by the optional *ProjectId fields. */
        val projectId: String,
        val leftNodeId: String,
        val rightNodeId: String,
        /** Optional — if set, resolves `leftNodeId` from this project instead of `projectId`. */
        val leftProjectId: String? = null,
        /** Optional — if set, resolves `rightNodeId` from this project instead of `projectId`. */
        val rightProjectId: String? = null,
    )

    @Serializable data class BodyFieldDiff(
        /** JSON path, e.g. `visualDescription`, `acts[1]`, `nested.field`. */
        val path: String,
        /** Null when the field is absent on the left side. */
        val leftValue: JsonElement? = null,
        /** Null when the field is absent on the right side. */
        val rightValue: JsonElement? = null,
    )

    @Serializable data class Output(
        val leftProjectId: String,
        val leftNodeId: String,
        val rightProjectId: String,
        val rightNodeId: String,
        val bothExist: Boolean,
        val leftExists: Boolean,
        val rightExists: Boolean,
        val kindChanged: Boolean,
        val leftKind: String,
        val rightKind: String,
        val contentHashChanged: Boolean,
        val leftContentHash: String,
        val rightContentHash: String,
        val bodyFieldDiffs: List<BodyFieldDiff>,
        val parentsAdded: List<String>,
        val parentsRemoved: List<String>,
    )

    override val id: String = "diff_source_nodes"
    override val helpText: String =
        "Compare two source nodes — within one project, or across two projects (e.g. a fork " +
            "vs its parent) — and report kind change, contentHash change, per-field body deltas, " +
            "and parent adds/removes. Use this to debug consistency drift, audit a " +
            "fork_source_node edit, or walk a generate→update history. Read-only; returns a " +
            "structured answer even when one side is missing."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Default project for both sides. Overridden per-side by leftProjectId / rightProjectId.")
            }
            putJsonObject("leftNodeId") { put("type", "string") }
            putJsonObject("rightNodeId") { put("type", "string") }
            putJsonObject("leftProjectId") {
                put("type", "string")
                put("description", "Optional — resolve leftNodeId from this project instead of projectId (cross-project diff).")
            }
            putJsonObject("rightProjectId") {
                put("type", "string")
                put("description", "Optional — resolve rightNodeId from this project instead of projectId (cross-project diff).")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("leftNodeId"),
                    JsonPrimitive("rightNodeId"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val leftProjectId = input.leftProjectId ?: input.projectId
        val rightProjectId = input.rightProjectId ?: input.projectId

        val leftProject = projects.get(ProjectId(leftProjectId))
            ?: error("Project $leftProjectId not found (left side)")
        val rightProject = projects.get(ProjectId(rightProjectId))
            ?: error("Project $rightProjectId not found (right side)")

        val leftNode = leftProject.source.byId[SourceNodeId(input.leftNodeId)]
        val rightNode = rightProject.source.byId[SourceNodeId(input.rightNodeId)]

        val leftExists = leftNode != null
        val rightExists = rightNode != null
        val bothExist = leftExists && rightExists

        val leftKind = leftNode?.kind.orEmpty()
        val rightKind = rightNode?.kind.orEmpty()
        val kindChanged = bothExist && leftKind != rightKind

        val leftHash = leftNode?.contentHash.orEmpty()
        val rightHash = rightNode?.contentHash.orEmpty()
        val contentHashChanged = bothExist && leftHash != rightHash

        val bodyDiffs = if (bothExist) {
            diffJson(leftNode!!.body, rightNode!!.body, path = "")
        } else {
            emptyList()
        }

        val (parentsAdded, parentsRemoved) = if (bothExist) {
            parentSetDelta(leftNode!!, rightNode!!)
        } else {
            emptyList<String>() to emptyList()
        }

        val out = Output(
            leftProjectId = leftProjectId,
            leftNodeId = input.leftNodeId,
            rightProjectId = rightProjectId,
            rightNodeId = input.rightNodeId,
            bothExist = bothExist,
            leftExists = leftExists,
            rightExists = rightExists,
            kindChanged = kindChanged,
            leftKind = leftKind,
            rightKind = rightKind,
            contentHashChanged = contentHashChanged,
            leftContentHash = leftHash,
            rightContentHash = rightHash,
            bodyFieldDiffs = bodyDiffs,
            parentsAdded = parentsAdded,
            parentsRemoved = parentsRemoved,
        )

        val outputForLlm = buildString {
            if (!bothExist) {
                append("missing: ")
                val missing = buildList {
                    if (!leftExists) add("left ${input.leftNodeId}@$leftProjectId")
                    if (!rightExists) add("right ${input.rightNodeId}@$rightProjectId")
                }
                append(missing.joinToString(", "))
                return@buildString
            }
            val same = !kindChanged &&
                !contentHashChanged &&
                bodyDiffs.isEmpty() &&
                parentsAdded.isEmpty() &&
                parentsRemoved.isEmpty()
            if (same) {
                append("${input.leftNodeId} and ${input.rightNodeId} are identical (same kind, hash, body, parents).")
                return@buildString
            }
            append("${input.leftNodeId}@$leftProjectId → ${input.rightNodeId}@$rightProjectId: ")
            val parts = mutableListOf<String>()
            if (kindChanged) parts += "kind $leftKind→$rightKind"
            if (contentHashChanged) parts += "hash ${leftHash.take(8)}→${rightHash.take(8)}"
            if (bodyDiffs.isNotEmpty()) parts += "${bodyDiffs.size} body field(s)"
            if (parentsAdded.isNotEmpty() || parentsRemoved.isNotEmpty()) {
                parts += "parents +${parentsAdded.size}/-${parentsRemoved.size}"
            }
            append(parts.joinToString("; "))
        }

        return ToolResult(
            title = "diff source nodes",
            outputForLlm = outputForLlm,
            data = out,
        )
    }

    private fun parentSetDelta(left: SourceNode, right: SourceNode): Pair<List<String>, List<String>> {
        val leftIds = left.parents.map { it.nodeId.value }.toSet()
        val rightIds = right.parents.map { it.nodeId.value }.toSet()
        val added = (rightIds - leftIds).sorted()
        val removed = (leftIds - rightIds).sorted()
        return added to removed
    }
}

// Recursive JSON differ scoped to this tool.
//
// Algorithm (intentionally simple — not a general-purpose JSON differ):
//  - JsonObjects descend key-by-key; paths concatenate with "." (empty path at root).
//    Keys present on only one side produce one BodyFieldDiff with the missing side
//    = null. Keys on both sides recurse.
//  - JsonArrays compare element-wise by index; path suffix is "[n]". Extra elements
//    on one side emit per-index diffs with the missing side = null. No attempt to
//    detect moved / renamed entries.
//  - Scalars (JsonPrimitive / JsonNull) compare by structural equality (the default
//    JsonElement.equals is enough — JsonPrimitive compares content + isString). If
//    they differ a single BodyFieldDiff is emitted at the current path.
//  - Type mismatches between sides (e.g. left is an object, right is a scalar) are
//    emitted as one diff at the current path — we do not descend into only one side.
private fun diffJson(left: JsonElement, right: JsonElement, path: String): List<DiffSourceNodesTool.BodyFieldDiff> {
    if (left == right) return emptyList()
    return when {
        left is JsonObject && right is JsonObject -> {
            val keys = (left.keys + right.keys).sorted()
            keys.flatMap { k ->
                val childPath = if (path.isEmpty()) k else "$path.$k"
                val l = left[k]
                val r = right[k]
                when {
                    l == null -> listOf(
                        DiffSourceNodesTool.BodyFieldDiff(childPath, leftValue = null, rightValue = r),
                    )
                    r == null -> listOf(
                        DiffSourceNodesTool.BodyFieldDiff(childPath, leftValue = l, rightValue = null),
                    )
                    else -> diffJson(l, r, childPath)
                }
            }
        }
        left is JsonArray && right is JsonArray -> {
            val size = maxOf(left.size, right.size)
            (0 until size).flatMap { i ->
                val childPath = "$path[$i]"
                val l = left.getOrNull(i)
                val r = right.getOrNull(i)
                when {
                    l == null -> listOf(
                        DiffSourceNodesTool.BodyFieldDiff(childPath, leftValue = null, rightValue = r),
                    )
                    r == null -> listOf(
                        DiffSourceNodesTool.BodyFieldDiff(childPath, leftValue = l, rightValue = null),
                    )
                    else -> diffJson(l, r, childPath)
                }
            }
        }
        // Type mismatch or scalar mismatch — one leaf diff at the current path.
        // JsonNull values are preserved verbatim; `null` in BodyFieldDiff means
        // "field absent on that side" (handled in the object/array branches above).
        else -> listOf(
            DiffSourceNodesTool.BodyFieldDiff(
                path = path,
                leftValue = left,
                rightValue = right,
            ),
        )
    }
}
