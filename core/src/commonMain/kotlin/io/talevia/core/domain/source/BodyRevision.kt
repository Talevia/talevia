package io.talevia.core.domain.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Snapshot of a [SourceNode.body] that existed before it was overwritten by
 * `update_source_node_body`. Persisted in the project bundle at
 * `<bundle>/source-history/<nodeId>.jsonl`, one entry per line in
 * chronological (oldest-first) order.
 *
 * Exposed to the agent via `source_query(select=history, root=<nodeId>)`,
 * which returns the most-recent N (default 20) revisions so "how did this
 * body evolve?" is answerable without requiring git log access to the
 * bundle.
 *
 * Full snapshot, not a diff — body sizes are small (structured JSON) and
 * reconstructing at read time from a diff chain costs more than the
 * duplication saves. [body] is typed `JsonElement` (not `JsonObject`)
 * because [SourceNode.body] itself is `JsonElement`; in practice every
 * kind keeps its body as a JsonObject, but the permissive typing avoids a
 * cast at read time and matches the SourceNode contract exactly.
 */
@Serializable
data class BodyRevision(
    /** The body as it existed at the time of overwriting. */
    val body: JsonElement,
    /**
     * Wall-clock epoch milliseconds when `update_source_node_body` replaced
     * this body with a newer one. Sourced from `Clock.System.now()` at the
     * mutation site.
     */
    val overwrittenAtEpochMs: Long,
)
