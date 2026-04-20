package io.talevia.core.tool.builtin

import io.talevia.core.PartId
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Part
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TodoPriority
import io.talevia.core.session.TodoStatus
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Agent scratchpad for multi-step work. Models OpenCode's `tool/todo.ts` /
 * `session/todo.ts` (behavioural reference) and Claude Code's TodoWrite tool.
 *
 * Each call fully replaces the current plan — the tool emits a single
 * [Part.Todos] onto the active assistant message, and downstream consumers
 * (CLI / desktop / server UIs) read the most recent `Part.Todos` via
 * [io.talevia.core.session.currentTodos]. Ride the existing Parts table
 * rather than minting a new one: plans are already session-scoped state and
 * the bus publishes `PartUpdated` for free.
 *
 * Permission is "todowrite" (default ALLOW): it's purely local state, cannot
 * exfiltrate data, and demanding a confirm each time would make the tool
 * useless for multi-step intents.
 */
class TodoWriteTool(private val clock: Clock = Clock.System) : Tool<TodoWriteTool.Input, TodoWriteTool.Output> {

    @Serializable
    data class Input(val todos: List<TodoInfo>)

    @Serializable
    data class Output(val count: Int, val todos: List<TodoInfo>)

    override val id: String = "todowrite"
    override val helpText: String = HELP_TEXT
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("todowrite")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("todos") {
                put("type", "array")
                put("description", "The updated todo list. Fully replaces the prior plan.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "Imperative description of the task (e.g. \"Run tests\").")
                        }
                        putJsonObject("status") {
                            put("type", "string")
                            put("description", "Task state.")
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("pending"))
                                    add(JsonPrimitive("in_progress"))
                                    add(JsonPrimitive("completed"))
                                    add(JsonPrimitive("cancelled"))
                                },
                            )
                        }
                        putJsonObject("priority") {
                            put("type", "string")
                            put("description", "Priority level. Optional; defaults to medium.")
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("high"))
                                    add(JsonPrimitive("medium"))
                                    add(JsonPrimitive("low"))
                                },
                            )
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("content"), JsonPrimitive("status"))))
                    put("additionalProperties", false)
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("todos"))))
        put("additionalProperties", false)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val now = clock.now()
        ctx.emitPart(
            Part.Todos(
                id = PartId(Uuid.random().toString()),
                messageId = ctx.messageId,
                sessionId = ctx.sessionId,
                createdAt = now,
                todos = input.todos,
            ),
        )
        val active = input.todos.count { it.status != TodoStatus.COMPLETED && it.status != TodoStatus.CANCELLED }
        return ToolResult(
            title = "$active open · ${input.todos.size} total",
            outputForLlm = renderForLlm(input.todos),
            data = Output(count = input.todos.size, todos = input.todos),
        )
    }

    internal fun renderForLlm(todos: List<TodoInfo>): String {
        if (todos.isEmpty()) return "(no todos)"
        return buildString {
            todos.forEachIndexed { idx, t ->
                if (idx > 0) append('\n')
                append(marker(t.status))
                append(' ')
                append(t.content)
                if (t.priority != TodoPriority.MEDIUM) {
                    append(" (")
                    append(t.priority.name.lowercase())
                    append(')')
                }
            }
        }
    }

    private fun marker(status: TodoStatus): String = when (status) {
        TodoStatus.PENDING -> "[ ]"
        TodoStatus.IN_PROGRESS -> "[~]"
        TodoStatus.COMPLETED -> "[x]"
        TodoStatus.CANCELLED -> "[-]"
    }

    companion object {
        /** Default permission rule — included by `DefaultPermissionRuleset`. */
        val ALLOW_RULE = PermissionRule(
            permission = "todowrite",
            pattern = "*",
            action = PermissionAction.ALLOW,
        )

        private val HELP_TEXT = """
            Maintain a structured todo list for multi-step work. Each call fully
            replaces the prior list.

            Use this proactively when:
              1. The user's request needs 3+ distinct steps.
              2. The task is non-trivial — multiple source edits, AIGC regenerations,
                 an export at the end.
              3. The user explicitly asks you to track progress.

            Do NOT use for single-call tasks ("add this clip"), informational Q&A,
            or anything that's already a one-shot tool invocation.

            Workflow:
              - Mark exactly one item `in_progress` at a time.
              - Flip an item to `completed` immediately after it's done; don't batch.
              - Use `cancelled` for items that became irrelevant — don't silently
                drop them.
              - Priorities (`high` / `medium` / `low`) are optional; default medium.

            The current todo list is persisted as a `Part.Todos` on this turn and is
            visible to every subsequent UI / CLI consumer of the session.
        """.trimIndent()
    }
}
