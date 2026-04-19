package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
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
 * @property parents References to upstream nodes (future DAG lane). Included in v1 so the
 *   field's serialized shape is stable before stale-propagation lands.
 * @property revision Monotonic per-node counter bumped on each in-place replacement.
 * @property contentHash Stubbed to `revision.toString()` today — the DAG lane will
 *   replace this with a real hash over `(kind, body, parents)`.
 */
@Serializable
data class SourceNode(
    val id: SourceNodeId,
    val kind: String,
    val body: JsonElement = JsonObject(emptyMap()),
    val parents: List<SourceRef> = emptyList(),
    val revision: Long = 0,
    // TODO(DAG): replace with real content hash over (kind, body, parents) once the
    //  stale-propagation lane lands. Today this is just a string of `revision` so
    //  downstream code can already read the field without us committing to the hash
    //  algorithm.
    val contentHash: String = revision.toString(),
)
