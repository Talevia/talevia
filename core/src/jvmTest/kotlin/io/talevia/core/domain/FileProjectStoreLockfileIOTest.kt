package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the lockfile JSONL I/O helpers in
 * `core/domain/FileProjectStoreLockfileIO.kt`:
 * [writeLockfileJsonl] and [readLockfileJsonl]. These
 * implement the phase-1 dual-write lockfile persistence
 * (jsonl alongside envelope) used by `FileProjectStore`'s
 * mutate path. Cycle 148 audit: 128 LOC.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Tail-malformed line is truncated; mid-file malformed
 *    line throws.** Per kdoc: "tail-truncate only applies to
 *    the last line." Tail recovery covers the legitimate
 *    crash-during-flush case (partial write at EOF). Mid-
 *    file silent truncation would silently lose the entries
 *    AFTER the malformed line, masking real corruption — so
 *    that path must throw loudly.
 *
 * 2. **Missing file → `null` (sentinel for "fall back to
 *    envelope").** Distinct from empty file → `Lockfile.EMPTY`
 *    ("jsonl exists but holds no entries"). Drift to "missing
 *    → EMPTY" would skip the envelope-fallback path,
 *    silently dropping the lockfile state of any pre-phase-1
 *    bundle on first read.
 *
 * 3. **Roundtrip preserves entry order and content
 *    bit-exact.** Per-line compact JSON encoding (NOT pretty)
 *    keeps the line-per-entry invariant; pretty-print would
 *    inject newlines mid-entry and break the read parse.
 *    Pinned by writing a 2-entry lockfile and reading it
 *    back to confirm both entries land in the same order.
 */
class FileProjectStoreLockfileIOTest {

    private val bundleRoot = "/bundle".toPath()
    private val jsonlPath = bundleRoot.resolve(FileProjectStore.LOCKFILE_JSONL)

    private fun lockfileEntry(input: String, asset: String) = LockfileEntry(
        inputHash = input,
        toolId = "generate_image",
        assetId = AssetId(asset),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
    )

    private fun fsWithBundleDir(): FakeFileSystem {
        val fs = FakeFileSystem()
        fs.createDirectories(bundleRoot)
        return fs
    }

    // ── missing file → null sentinel ──────────────────────────────

    @Test fun missingJsonlReturnsNullNotEmptyLockfile() {
        // The marquee envelope-fallback pin: missing file
        // returns null so the caller knows to fall through to
        // the envelope's `lockfile` field. EMPTY would skip
        // that fallback and silently zero out pre-phase-1
        // bundles.
        val fs = fsWithBundleDir()
        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertNull(out, "missing file → null sentinel, NOT Lockfile.EMPTY")
    }

    // ── empty / blank file → Lockfile.EMPTY ──────────────────────

    @Test fun emptyFileReturnsLockfileEMPTY() {
        // Pin: 0-byte file → Lockfile.EMPTY (NOT null). The
        // distinction lets the precedence rule fire correctly:
        // jsonl exists but holds 0 entries IS authoritative
        // (don't fall back to envelope just because list is
        // empty).
        val fs = fsWithBundleDir()
        fs.write(jsonlPath) { writeUtf8("") }

        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertEquals(Lockfile.EMPTY, out, "empty file → EMPTY (NOT null)")
    }

    @Test fun whitespaceOnlyFileReturnsEMPTY() {
        // Pin: `text.isBlank()` early-out catches whitespace-
        // only files (e.g. accidental whitespace from manual
        // edit). Returns EMPTY same as truly-empty.
        val fs = fsWithBundleDir()
        fs.write(jsonlPath) { writeUtf8("   \n\n  ") }

        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertEquals(Lockfile.EMPTY, out)
    }

    @Test fun fileWithOnlyTrailingNewlineNoEntriesReturnsEMPTY() {
        // Pin: `\n` alone has split → ["", ""]; filter
        // isNotEmpty leaves []; nonEmpty.isEmpty() → EMPTY.
        // Distinguish from genuinely-blank text via the
        // earlier `isBlank()` check (which catches "\n" too,
        // but this exercise covers the second guard).
        val fs = fsWithBundleDir()
        fs.write(jsonlPath) { writeUtf8("\n\n\n") }

        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertEquals(Lockfile.EMPTY, out)
    }

    // ── round-trip (entry order + bit-exact content) ──────────────

    @Test fun roundTripPreservesEntriesInOrder() {
        // Marquee roundtrip pin: write 2 entries, read back,
        // confirm order + content match.
        val fs = fsWithBundleDir()
        val entry1 = lockfileEntry("h1", "asset-1")
        val entry2 = lockfileEntry("h2", "asset-2")
        val original = EagerLockfile(entries = listOf(entry1, entry2))

        writeLockfileJsonl(fs, bundleRoot, original, JsonConfig.default)

        val readBack = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertEquals(2, readBack!!.entries.size)
        assertEquals(entry1, readBack.entries[0])
        assertEquals(entry2, readBack.entries[1])
    }

    @Test fun writeIsCompactJsonOneLinePerEntryNotPretty() {
        // Pin: per-line compact encoding (NOT pretty). Pretty
        // would break the line-per-entry invariant by
        // injecting newlines inside each entry's JSON.
        val fs = fsWithBundleDir()
        val lockfile = EagerLockfile(
            entries = listOf(
                lockfileEntry("h1", "a1"),
                lockfileEntry("h2", "a2"),
                lockfileEntry("h3", "a3"),
            ),
        )
        writeLockfileJsonl(fs, bundleRoot, lockfile, JsonConfig.default)

        val text = fs.read(jsonlPath) { readUtf8() }
        // Pin: exactly 3 newlines (one per entry's trailing \n).
        assertEquals(
            3,
            text.count { it == '\n' },
            "compact JSON: 3 entries → 3 newlines; got: '$text'",
        )
        // Pin: each line is independently parseable.
        text.split('\n').filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            val parsed = JsonConfig.default.decodeFromString(LockfileEntry.serializer(), line)
            assertEquals("h${i + 1}", parsed.inputHash)
        }
    }

    @Test fun writeEmptyLockfileProducesEmptyFile() {
        // Pin: per kdoc "Empty entries → empty file (preserves
        // 'lockfile.jsonl exists → it's authoritative'
        // precedence)". A subsequent read returns
        // `Lockfile.EMPTY`, NOT null — the file's existence
        // is what matters for envelope-fallback semantics.
        val fs = fsWithBundleDir()
        writeLockfileJsonl(fs, bundleRoot, Lockfile.EMPTY, JsonConfig.default)

        assertTrue(fs.exists(jsonlPath), "empty lockfile still creates the file")
        val text = fs.read(jsonlPath) { readUtf8() }
        assertEquals("", text, "0-entry lockfile → 0-byte file")
        // And reading back returns EMPTY (NOT null).
        assertEquals(
            Lockfile.EMPTY,
            readLockfileJsonl(fs, bundleRoot, JsonConfig.default),
        )
    }

    // ── crash-recovery: tail-truncate vs mid-file throw ──────────

    @Test fun tailMalformedLineSilentlyTruncated() {
        // The marquee crash-recovery pin: a partial write
        // before flush leaves the LAST line as malformed JSON.
        // The reader must drop just that line and return the
        // earlier well-formed entries (silently — the next
        // mutate rewrites cleanly).
        val fs = fsWithBundleDir()
        val good = JsonConfig.default.encodeToString(
            LockfileEntry.serializer(),
            lockfileEntry("h1", "a1"),
        )
        // Write good entry + tail-malformed line (no trailing \n).
        fs.write(jsonlPath) { writeUtf8("$good\n{\"inputHash\":\"h-broken") }

        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertEquals(1, out!!.entries.size, "tail-malformed silently dropped")
        assertEquals("h1", out.entries.single().inputHash)
    }

    @Test fun midFileMalformedLineThrowsLoudly() {
        // The marquee never-silently-corrupt pin: a malformed
        // line in the middle (well-formed line after) is real
        // corruption — silent truncation would lose
        // everything after. Must throw.
        val fs = fsWithBundleDir()
        val good1 = JsonConfig.default.encodeToString(
            LockfileEntry.serializer(),
            lockfileEntry("h1", "a1"),
        )
        val good2 = JsonConfig.default.encodeToString(
            LockfileEntry.serializer(),
            lockfileEntry("h2", "a2"),
        )
        // Sandwich a malformed line between two well-formed.
        fs.write(jsonlPath) { writeUtf8("$good1\n{\"oops\":missing-close\n$good2\n") }

        val ex = assertFailsWith<IllegalStateException> {
            readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        }
        val msg = ex.message.orEmpty()
        assertTrue("malformed entry" in msg, "diagnostic surfaces; got: $msg")
        assertTrue("line 2" in msg, "line number surfaces; got: $msg")
        assertTrue(
            "tail-truncate only applies to the last line" in msg,
            "rationale surfaces; got: $msg",
        )
    }

    @Test fun singleMalformedLineWhenItIsAlsoTheTailIsTruncated() {
        // Pin: idx==lastIndex with one entry that is itself
        // malformed → silently truncate to empty. Edge case
        // of "first IS last".
        val fs = fsWithBundleDir()
        fs.write(jsonlPath) { writeUtf8("{\"oops\":") }

        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertEquals(0, out!!.entries.size, "single-line malformed = tail = truncate")
    }

    // ── trailing newline tolerance ────────────────────────────────

    @Test fun fileEndingWithTrailingNewlineParsesAllRealEntries() {
        // Pin: the canonical write shape ends every entry
        // with \n, so the read split('\n') yields N+1 chunks
        // where the last is empty. The `filter
        // { it.isNotEmpty() }` step drops it. Drift to "treat
        // last empty as malformed" would throw on every
        // canonically-written file.
        val fs = fsWithBundleDir()
        val entry = lockfileEntry("h1", "a1")
        // Write via the prod helper (which appends trailing \n
        // per entry) — confirms the canonical shape parses
        // back without truncation drama.
        writeLockfileJsonl(
            fs,
            bundleRoot,
            EagerLockfile(entries = listOf(entry)),
            JsonConfig.default,
        )

        val text = fs.read(jsonlPath) { readUtf8() }
        assertTrue(text.endsWith("\n"), "canonical write ends with \\n")

        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)
        assertEquals(1, out!!.entries.size, "entry parsed despite trailing newline")
    }

    // ── atomic write ──────────────────────────────────────────────

    @Test fun writeOverridesExistingFileCompletelyNotAppended() {
        // Pin: phase-1 is whole-file-write (not append). A
        // second write completely replaces the previous file.
        // Drift to append would double up entries.
        val fs = fsWithBundleDir()
        val first = EagerLockfile(entries = listOf(lockfileEntry("old", "a-old")))
        writeLockfileJsonl(fs, bundleRoot, first, JsonConfig.default)

        val second = EagerLockfile(entries = listOf(lockfileEntry("new", "a-new")))
        writeLockfileJsonl(fs, bundleRoot, second, JsonConfig.default)

        val out = readLockfileJsonl(fs, bundleRoot, JsonConfig.default)!!
        assertEquals(1, out.entries.size, "second write replaces, doesn't append")
        assertEquals("new", out.entries.single().inputHash)
    }
}
