package io.talevia.core.domain

import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Lockfile JSONL I/O helpers (`lockfile-extract-jsonl-phase1`).
 *
 * Phase 1 introduces `<bundleRoot>/lockfile.jsonl` — one [LockfileEntry] per
 * line, append-only semantically (entries are never edited; `prune_lockfile`
 * rewrites the whole file when removing). Phase 1 writes the **whole file**
 * via [atomicWrite] each time the lockfile changes; phase 2
 * (`lockfile-extract-jsonl-phase2-lazy-load`) will switch to true file-
 * append + lazy stream-load for the O(1)-open perf win.
 *
 * **Dual-write contract** (phase 1 only): `writeBundleLocked` writes both
 * the JSONL **and** the envelope's `lockfile` field. JSONL goes first so on
 * crash between the two writes, the more-recent state is recoverable from
 * the JSONL — envelope might be a tmp-rename atomic snapshot of the older
 * state, JSONL has the new entries.
 *
 * **Read precedence**: when both files exist, JSONL wins. When only the
 * envelope has a populated `lockfile`, that's a pre-cycle-24 bundle —
 * read from envelope and on next write the JSONL gets created. When both
 * are missing, [Lockfile.EMPTY].
 *
 * **Crash recovery**: if the last line of JSONL is malformed (e.g. partial
 * write before flush), [readLockfileJsonl] truncates it from the in-memory
 * result and proceeds. The on-disk file is **not** rewritten by reads —
 * that happens on the next write. This keeps the read path side-effect-
 * free; recovery is monotone (the next write overwrites the malformed
 * tail anyway since we're whole-file-writing in phase 1).
 *
 * Pure functions against an Okio [FileSystem]; mirrors the
 * [FileProjectStoreEnvelopeIO] pattern. Phase 2 will add a streaming
 * `findByInputHash`-style lazy interface that doesn't materialise all
 * entries on open.
 */

/**
 * Write the lockfile to `<path>/lockfile.jsonl` as one [LockfileEntry] per
 * line, atomically. Empty entries → empty file (preserves "lockfile.jsonl
 * exists → it's authoritative" precedence; an empty bundle that previously
 * only existed in envelope-only form gets a 0-byte jsonl on first mutate).
 *
 * Atomicity: tmp + rename like [writeStoredEnvelope]. Per-line entry encode
 * uses [json] (caller's [Json] instance — usually [io.talevia.core.JsonConfig.prettyPrint]
 * for git-friendly diffs, though jsonl per-line entries are already line-
 * stable so the prettyPrint vs default choice doesn't drift git-diff
 * stability here).
 */
internal fun writeLockfileJsonl(
    fs: FileSystem,
    path: Path,
    lockfile: Lockfile,
    json: Json,
) {
    atomicWrite(fs, path.resolve(FileProjectStore.LOCKFILE_JSONL)) {
        for (entry in lockfile.entries) {
            // Per-line: each entry is its own JSON object, no surrounding array.
            // Compact (single-line) encoding by using a non-pretty Json instance
            // — pretty-print would emit multi-line objects, breaking the line-
            // per-entry invariant. Use the canonical `io.talevia.core.JsonConfig.default`
            // so per-entry encoding is stable across cycles.
            writeUtf8(io.talevia.core.JsonConfig.default.encodeToString(LockfileEntry.serializer(), entry))
            writeUtf8("\n")
        }
    }
}

/**
 * Read `<path>/lockfile.jsonl` as a [Lockfile]. Returns `null` if the file
 * doesn't exist (caller falls back to the envelope's lockfile field —
 * migration path for pre-phase-1 bundles).
 *
 * Per-line decode tolerates a malformed last line (partial write before
 * crash): the malformed entry is silently dropped from the result. Earlier
 * malformed lines (i.e. mid-file corruption) propagate as exceptions —
 * mid-file corruption is a real bug, not a partial-write artifact, and
 * silently truncating from a non-tail position would lose data.
 *
 * Empty file → [Lockfile.EMPTY] (semantically "lockfile exists but holds
 * no entries"; distinct from "lockfile.jsonl is missing entirely").
 */
internal fun readLockfileJsonl(
    fs: FileSystem,
    path: Path,
    json: Json,
): Lockfile? {
    val jsonlPath = path.resolve(FileProjectStore.LOCKFILE_JSONL)
    if (!fs.exists(jsonlPath)) return null

    val text = fs.read(jsonlPath) { readUtf8() }
    if (text.isBlank()) return Lockfile.EMPTY

    val rawLines = text.split('\n')
    // The last line is empty when the file ends in '\n' (the canonical
    // shape we write); not malformed, just trailing newline.
    val nonEmpty = rawLines.filter { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) return Lockfile.EMPTY

    val entries = mutableListOf<LockfileEntry>()
    val lastIndex = nonEmpty.lastIndex
    for ((idx, line) in nonEmpty.withIndex()) {
        val parsed = runCatching {
            io.talevia.core.JsonConfig.default.decodeFromString(LockfileEntry.serializer(), line)
        }
        if (parsed.isSuccess) {
            entries += parsed.getOrThrow()
        } else if (idx == lastIndex) {
            // Tail malformed line — partial write before flush. Silently
            // truncate from the in-memory view; the on-disk file gets
            // rewritten cleanly on the next mutate.
        } else {
            // Mid-file malformed line — propagate. Silent truncation would
            // lose data and mask real corruption.
            throw IllegalStateException(
                "lockfile.jsonl: malformed entry at line ${idx + 1} (file = ${jsonlPath}); " +
                    "tail-truncate only applies to the last line. Inspect the bundle.",
            )
        }
    }
    return Lockfile(entries = entries)
}
