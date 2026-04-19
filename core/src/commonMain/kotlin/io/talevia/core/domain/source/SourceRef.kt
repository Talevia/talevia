package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import kotlinx.serialization.Serializable

/**
 * A typed reference from one [SourceNode] to another.
 *
 * Kept as a thin wrapper (rather than a raw [SourceNodeId]) so the DAG lane can add
 * optional fields (role, version pin, etc.) without breaking the serialized shape.
 * The serialized form today is just `{ "nodeId": "..." }`, which
 * `ignoreUnknownKeys = true` will keep compatible as the struct grows.
 */
@Serializable
data class SourceRef(
    val nodeId: SourceNodeId,
)
