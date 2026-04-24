package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.BodyRevision
import okio.FileSystem
import okio.Path

/**
 * Per-source-node body-history persistence helpers for [FileProjectStore].
 *
 * Files live at `<bundle>/source-history/<nodeId>.jsonl` — one JSONL per
 * node, append-only, newest-last. Cycle-45 landed the read + write
 * surface; cycle-49 extracted the I/O impl to this file as part of the
 * `debt-split-file-project-store` refactor so adding a second history
 * lane (e.g. timeline-snapshot history) doesn't force another round of
 * file-length triage on `FileProjectStore.kt` itself.
 *
 * Pure functions against [FileSystem] + [Path] + [SourceNodeId] +
 * [BodyRevision]. JSONL encoding uses [JsonConfig.default] (single-line
 * compact) NOT the store's pretty-print [kotlinx.serialization.json.Json]
 * — pretty JSON has embedded newlines which would break per-line reading.
 *
 * Caller ([FileProjectStore]) holds its intra-process mutex around these
 * calls; the helpers themselves are lock-free so they can be called from
 * any synchronisation shape.
 */

/**
 * Append a single [BodyRevision] to `<bundle>/source-history/<nodeId>.jsonl`,
 * creating the directory / file as needed. Read-modify-atomicWrite rather
 * than `fs.appendingSink()` + `.use { }` because the latter's resolution
 * through Okio's `Sink` fails on Kotlin/Native (documented in
 * `docs/ENGINEERING_NOTES.md`'s `SharedFlow.subscriptionCount` entry +
 * cycle-45's decision). One-line-per-revision means rewriting the whole
 * file per append costs O(N) where N is the current history size —
 * negligible on the real bound (20-ish entries per node).
 */
internal fun appendSourceNodeHistoryFile(
    fs: FileSystem,
    bundlePath: Path,
    nodeId: SourceNodeId,
    revision: BodyRevision,
) {
    val historyDir = bundlePath.resolve(FileProjectStore.SOURCE_HISTORY_DIR)
    fs.createDirectories(historyDir)
    val file = historyDir.resolve("${nodeId.value}.jsonl")
    val line = JsonConfig.default.encodeToString(BodyRevision.serializer(), revision)
    val existing = if (fs.exists(file)) fs.read(file) { readUtf8() } else ""
    atomicWrite(fs, file) {
        writeUtf8(existing)
        writeUtf8(line)
        writeUtf8("\n")
    }
}

/**
 * Read up to [limit] most-recent revisions for the given node, newest-
 * first. Returns empty on missing file (a never-updated node legitimately
 * has no history — see `runHistoryQuery` for the narrative that
 * distinguishes this from "unknown node id").
 *
 * JSONL is append-order (oldest-first); the reversal happens here so
 * callers don't have to reason about storage order.
 */
internal fun listSourceNodeHistoryFile(
    fs: FileSystem,
    bundlePath: Path,
    nodeId: SourceNodeId,
    limit: Int,
): List<BodyRevision> {
    val file = bundlePath.resolve(FileProjectStore.SOURCE_HISTORY_DIR).resolve("${nodeId.value}.jsonl")
    if (!fs.exists(file)) return emptyList()
    val text = fs.read(file) { readUtf8() }
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    return lines.asReversed().asSequence().take(limit).map {
        JsonConfig.default.decodeFromString(BodyRevision.serializer(), it)
    }.toList()
}
