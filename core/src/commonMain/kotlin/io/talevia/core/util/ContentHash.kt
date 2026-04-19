package io.talevia.core.util

import io.talevia.core.JsonConfig
import io.talevia.core.domain.source.SourceRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic content fingerprinting for the Source DAG (VISION §3.2).
 *
 * Used by [io.talevia.core.domain.source.SourceNode.contentHash] to give each node a
 * value-level identity. Two nodes with identical `(kind, body, parents)` hash the same,
 * so "source changed vs. didn't change" is answerable without diffing JSON blobs at
 * every read.
 *
 * We use **FNV-1a 64-bit**, hex-encoded. Rationale, recorded in `docs/DECISIONS.md`:
 *   - Pure Kotlin, zero dependency, stable across JVM / iOS / Android — picking a
 *     stdlib-less hash avoided taking on a crypto dep for the scaffolding commit.
 *   - Not cryptographic. Inside one project (10²–10³ nodes) FNV-1a 64's collision
 *     probability is negligible. If/when we move to a **content-addressed remote
 *     artifact cache** (shared across projects or users), we swap this for SHA-256.
 *     The upgrade path is a single-function change: every caller goes through
 *     [contentHashOf], and [SourceNode.contentHash] is re-derived on every write.
 */
internal fun fnv1a64Hex(input: String): String {
    // 64-bit FNV-1a (offset basis & prime per the reference spec).
    var hash = -0x340d631b7bdddcdbL // 0xCBF29CE484222325 as a signed Long
    val prime = 0x100000001B3L
    // Iterate UTF-16 code units: we control the inputs (canonical JSON = ASCII-heavy),
    // so we do not need full UTF-8 encoding to stay deterministic — the same String
    // hashes the same on every platform.
    for (i in input.indices) {
        hash = hash xor input[i].code.toLong()
        hash = hash * prime
    }
    // ULong so we get a consistent unsigned hex on all platforms (no sign prefix).
    return hash.toULong().toString(16).padStart(16, '0')
}

/**
 * Compute a content hash over `(kind, body, parents)`. Stable under:
 *  - reordering of the input arguments (we hash each argument distinctly)
 *  - re-serialization (we use the canonical [JsonConfig.default] for body + parents)
 *
 * Not stable under JSON property reordering inside [body] — `kotlinx-serialization`
 * emits fields in declaration order, which is deterministic per serializer, so this
 * is a constraint on schema authors rather than on us here.
 */
internal fun contentHashOf(
    kind: String,
    body: JsonElement,
    parents: List<SourceRef>,
): String {
    val json = JsonConfig.default
    // Use the JSON primitive for `kind` so a kind string containing `|` cannot forge
    // a collision with a different `kind,body` split.
    val kindJson = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(kind))
    val bodyJson = json.encodeToString(JsonElement.serializer(), body)
    val parentsJson = json.encodeToString(ListSerializer(SourceRef.serializer()), parents)
    return fnv1a64Hex("$kindJson|$bodyJson|$parentsJson")
}
