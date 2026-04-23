package io.talevia.core.tool.query

import io.talevia.core.tool.Tool
import kotlinx.serialization.KSerializer

/**
 * Base for unified read-only query tools — the shape shared by
 * `project_query` / `session_query` / `source_query` / `provider_query`.
 *
 * Each concrete subclass advertises:
 *  - the canonical lowercase set of [selects] it accepts, and
 *  - a [rowSerializerFor] registry mapping each select to the serializer
 *    that can decode the `Output.rows` [kotlinx.serialization.json.JsonArray].
 *
 * The shared value of this base is two-fold:
 *  1. `Output.rows` has always been an opaque `JsonArray` — callers decode
 *     with a per-select row serializer. Publishing the registry on the tool
 *     itself lets test-kit helpers (and a future CLI `--json` formatter)
 *     decode rows without each caller re-importing the concrete `XRow`
 *     type. This is the primary payoff of the abstraction.
 *  2. [canonicalSelect] replaces the identical
 *     `trim().lowercase() + if (!in ALL_SELECTS) error(…)` four-liner that
 *     each dispatcher used to inline.
 *
 * This base intentionally does **not** own dispatch (the `when (select)`
 * branch). Each query tool has per-tool prefix work — resolving a project,
 * computing pagination bounds, loading a session, etc. — that varies
 * enough per tool that trying to factor a single `selectHandlers: Map<…>`
 * into the base would push a handler-signature abstraction onto all four.
 * The `when` branch stays per-tool so handlers keep their natural
 * per-tool signatures (`runTracksQuery(project, input, limit, offset)`
 * reads cleaner than a uniformly-parametrised lambda). Revisit only if a
 * fifth query tool shows the dispatch block is itself a long-file
 * contributor — at 14–15 selects per tool today the dispatch `when` is
 * ~20 lines, well short of the long-file threshold.
 *
 * Row types are required to be top-level per the
 * `<ToolName>Query.kt` sibling file convention (see
 * `core/tool/builtin/<area>/query/`). Keeping `@Serializable data class`
 * rows top-level means moving handlers between files without dragging the
 * row out, and lets tests / future consumers import the row type by its
 * natural `<Area>Row` name rather than `<Tool>.<Area>Row`. The
 * `QueryDispatcherConventionTest` in `commonTest` asserts this on every
 * concrete dispatcher.
 */
abstract class QueryDispatcher<I : Any, O : Any> : Tool<I, O> {

    /**
     * The canonical (lowercase, trimmed) set of selects this tool accepts.
     * Must match the keys covered by [rowSerializerFor] — the convention
     * test enforces the `selects` ↔ `rowSerializerFor` agreement.
     */
    abstract val selects: Set<String>

    /**
     * The serializer for the row type emitted by this [select]. Always the
     * concrete row serializer — NOT a `ListSerializer(...)` wrapper;
     * callers wrap when they want the full `Output.rows` list.
     *
     * Throws if [select] isn't in [selects]. Concrete subclasses typically
     * implement as a `when` covering every select.
     */
    abstract fun rowSerializerFor(select: String): KSerializer<*>

    /**
     * Normalise [raw] (trim + lowercase) and verify it's one of [selects].
     * Replaces the inlined 4-line block every dispatcher used to own.
     *
     * Fails loud with the list of known selects so the LLM's next turn
     * sees valid options rather than a silent empty result.
     */
    protected fun canonicalSelect(raw: String): String {
        val s = raw.trim().lowercase()
        if (s !in selects) {
            error(
                "select must be one of ${selects.sorted().joinToString(", ")} " +
                    "(got '$raw')",
            )
        }
        return s
    }
}
