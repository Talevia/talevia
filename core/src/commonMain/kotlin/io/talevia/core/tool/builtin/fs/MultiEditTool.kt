package io.talevia.core.tool.builtin.fs

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.FileSystem
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
 * Apply multiple find-and-replace operations to a single file in one call.
 * Mirrors Claude Code's MultiEdit and OpenCode's `tool/multiedit.ts`.
 *
 * Sequential semantics: edit N is applied to the result of edit N-1, so the
 * model can plan a chain like "rename Foo → Bar then add a new method below
 * the renamed declaration" without two round-trips. Atomic: all edits validate
 * + apply in memory first; the file is written once at the end. If any edit
 * fails (oldString missing, or matches multiple times without replaceAll),
 * nothing is written and the disk file is left untouched.
 *
 * Permission reuses [EditTool]'s `fs.write` with the same `path` pattern field
 * — an "Always allow fs.write on /tmp/foo" decision covers both tools.
 */
class MultiEditTool(private val fs: FileSystem) : Tool<MultiEditTool.Input, MultiEditTool.Output> {
    @Serializable
    data class EditOp(
        val oldString: String,
        val newString: String,
        val replaceAll: Boolean = false,
    )

    @Serializable
    data class Input(
        val path: String,
        val edits: List<EditOp>,
    )

    @Serializable
    data class EditResult(
        val replacements: Int,
    )

    @Serializable
    data class Output(
        val path: String,
        val totalReplacements: Int,
        val bytesWritten: Long,
        val perEdit: List<EditResult>,
    )

    override val id: String = "multi_edit"
    override val helpText: String =
        "Apply a sequence of find-and-replace edits to a single UTF-8 text file in one call. " +
            "Each edit operates on the result of the previous one. All edits must validate or " +
            "the file is left untouched (atomic). Each edit's `oldString` must be literally " +
            "present, and unique unless `replaceAll=true`. Prefer over `edit_file` when you " +
            "need several changes in one file. Not for Project JSON / ~/.talevia/ / media catalog."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "fs.write",
        patternFrom = { raw -> extractPathPattern(raw, field = "path") },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the file to edit (must exist).")
            }
            putJsonObject("edits") {
                put("type", "array")
                put(
                    "description",
                    "Edits to apply sequentially. Each operates on the result of the previous one.",
                )
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("oldString") {
                            put("type", "string")
                            put(
                                "description",
                                "Literal substring to find. Must be present, and unique unless replaceAll=true.",
                            )
                        }
                        putJsonObject("newString") {
                            put("type", "string")
                            put("description", "Replacement. May be empty to delete the match.")
                        }
                        putJsonObject("replaceAll") {
                            put("type", "boolean")
                            put(
                                "description",
                                "If true, replace every occurrence; otherwise oldString must be unique.",
                            )
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("oldString"), JsonPrimitive("newString"))),
                    )
                    put("additionalProperties", false)
                }
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("edits"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.edits.isNotEmpty()) { "edits must not be empty" }
        var content = fs.readText(input.path)
        val perEdit = ArrayList<EditResult>(input.edits.size)
        var total = 0
        input.edits.forEachIndexed { idx, op ->
            require(op.oldString.isNotEmpty()) {
                "edits[$idx]: oldString must not be empty — use write_file to create / overwrite a file"
            }
            require(op.oldString != op.newString) {
                "edits[$idx]: oldString and newString are identical — no edit would occur"
            }
            val occurrences = countOccurrences(content, op.oldString)
            when {
                occurrences == 0 ->
                    throw IllegalArgumentException(
                        "edits[$idx]: oldString not found in ${input.path} after applying $idx prior edit(s). " +
                            "Edits operate on the running result; check that earlier edits did not " +
                            "consume the text this edit was looking for.",
                    )
                occurrences > 1 && !op.replaceAll ->
                    throw IllegalArgumentException(
                        "edits[$idx]: oldString matches $occurrences times in ${input.path}. " +
                            "Pass replaceAll=true to replace all, or widen oldString to uniquely identify the site.",
                    )
            }
            content = if (op.replaceAll) {
                content.replace(op.oldString, op.newString)
            } else {
                val pos = content.indexOf(op.oldString)
                content.substring(0, pos) + op.newString + content.substring(pos + op.oldString.length)
            }
            val applied = if (op.replaceAll) occurrences else 1
            perEdit.add(EditResult(applied))
            total += applied
        }
        val bytes = fs.writeText(input.path, content)
        return ToolResult(
            title = "multi_edit ${input.path}",
            outputForLlm = "applied ${input.edits.size} edit(s), $total total replacement(s) in ${input.path}",
            data = Output(
                path = input.path,
                totalReplacements = total,
                bytesWritten = bytes,
                perEdit = perEdit,
            ),
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            count++
            from = idx + needle.length
        }
        return count
    }
}
