package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import io.talevia.core.util.contentHashOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A single node in a [Source] graph.
 *
 * Core intentionally does **not** know the shape of [body]. [kind] is a dotted namespace
 * string (e.g. `"vlog.raw_footage"`, `"narrative.character"`) — genre extensions encode
 * their own typed body via [kotlinx.serialization.json.Json.encodeToJsonElement] and
 * decode on read. This is the boundary that keeps Core free of genre schemas
 * (VISION §2.1, CLAUDE.md anti-requirement "在 Core 里硬编码某一个 genre 的 source schema").
 *
 * @property id Stable identity — survives edits to [body].
 * @property kind Dotted-namespace tag. Owners of a namespace decide their own versioning.
 * @property body Opaque payload. Typed genre accessors round-trip it through the canonical
 *   [io.talevia.core.JsonConfig.default]. Defaults to [JsonObject] empty for nodes that
 *   carry no body (pure relationship nodes).
 * @property parents References to upstream nodes in the Source DAG. An edit to any
 *   ancestor flows through [Source.stale] to mark this node (and its descendants) as
 *   needing recomputation.
 * @property revision Monotonic per-node counter bumped on each in-place replacement.
 *   Useful for debugging / UI ordering — *not* a substitute for [contentHash] in cache
 *   keys, because two unrelated edits can produce the same revision.
 * @property contentHash Deterministic fingerprint over `(kind, body, parents)`. Stable
 *   across re-encodings, unaffected by [revision]. This is what cache keys downstream
 *   of Source key off of (per VISION §3.2 "cache key is source hash + toolchain version").
 */
@Serializable
data class SourceNode(
    val id: SourceNodeId,
    val kind: String,
    val body: JsonElement = JsonObject(emptyMap()),
    val parents: List<SourceRef> = emptyList(),
    val revision: Long = 0,
    val contentHash: String = contentHashOf(kind, body, parents),
) {
    companion object {
        /**
         * Construct a node with a correctly computed [contentHash]. Prefer this over the
         * raw data-class constructor when creating nodes outside of [Source.addNode] /
         * [Source.replaceNode] (e.g., in tests or adapters reading nodes from external
         * systems). All mutation helpers in [SourceMutations] go through this path.
         */
        fun create(
            id: SourceNodeId,
            kind: String,
            body: JsonElement = JsonObject(emptyMap()),
            parents: List<SourceRef> = emptyList(),
            revision: Long = 0,
        ): SourceNode = SourceNode(
            id = id,
            kind = kind,
            body = body,
            parents = parents,
            revision = revision,
            contentHash = contentHashOf(kind, body, parents),
        )
    }
}
