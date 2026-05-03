package io.talevia.core.domain

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.BodyRevision
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for the two source-node body-history I/O
 * helpers in `core/domain/FileProjectStoreSourceHistoryIO.kt`:
 * [appendSourceNodeHistoryFile] and
 * [listSourceNodeHistoryFile]. Cycle 147 audit: 79 LOC, 0
 * transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Append creates the dir + file as needed; never throws
 *    on a fresh node.** First write to a never-touched node
 *    must not require pre-existing scaffolding. A regression
 *    that asserted dir existence would crash the very first
 *    `update_source_node_body` call against a fresh project,
 *    invisible until then.
 *
 * 2. **JSONL stores oldest-first; read returns newest-first.**
 *    Per kdoc: "JSONL is append-order (oldest-first); the
 *    reversal happens here so callers don't have to reason
 *    about storage order." A regression that returned
 *    storage order (oldest-first) would surface stale
 *    revisions in `source_query(select=history)` while the
 *    just-edited body got hidden behind older drafts.
 *
 * 3. **Missing file → empty list, NOT exception.** Per kdoc:
 *    "a never-updated node legitimately has no history."
 *    Distinguishes "never written" from "unknown node id"
 *    (the latter is the dispatcher's responsibility) — a
 *    regression that threw FileNotFoundException would
 *    pollute the agent narrative for the most common case
 *    (querying a brand-new node before any body update).
 */
class FileProjectStoreSourceHistoryIOTest {

    private val bundlePath = "/bundle".toPath()

    private fun bodyRevision(
        bodyKey: String,
        bodyValue: String,
        overwrittenAtEpochMs: Long,
    ): BodyRevision = BodyRevision(
        body = buildJsonObject { put(bodyKey, JsonPrimitive(bodyValue)) },
        overwrittenAtEpochMs = overwrittenAtEpochMs,
    )

    // ── append: create-on-demand ─────────────────────────────────

    @Test fun appendCreatesDirAndFileForFreshNode() {
        // Marquee create-on-demand pin: the first write to a
        // node must materialise both the source-history dir
        // AND the per-node JSONL file.
        val fs = FakeFileSystem()
        // Note: we don't pre-create the bundle dir or the
        // source-history dir. The function must do both.
        val nodeId = SourceNodeId("fresh-node")
        appendSourceNodeHistoryFile(
            fs = fs,
            bundlePath = bundlePath,
            nodeId = nodeId,
            revision = bodyRevision("k", "v", 1_000L),
        )

        // Pin: file exists at the expected path.
        val expected = bundlePath
            .resolve(FileProjectStore.SOURCE_HISTORY_DIR)
            .resolve("fresh-node.jsonl")
        assertTrue(fs.exists(expected), "JSONL file created; got fs root listing")
        // Pin: file content is one line of JSON + trailing newline.
        val content = fs.read(expected) { readUtf8() }
        assertTrue(content.endsWith("\n"), "trailing newline; got: '$content'")
        assertEquals(1, content.lineSequence().filter { it.isNotBlank() }.count())
    }

    @Test fun appendToExistingNodePreservesPriorRevisionsInOrder() {
        // Pin: append-only invariant. Read-then-write semantic
        // means the old line stays at top, new line at bottom.
        // Regression to "always overwrite" would silently lose
        // history.
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("editable")
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "first", 100L))
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "second", 200L))
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "third", 300L))

        val file = bundlePath.resolve(FileProjectStore.SOURCE_HISTORY_DIR).resolve("editable.jsonl")
        val lines = fs.read(file) { readUtf8() }.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(3, lines.size, "all three revisions present")
        // Pin: storage is oldest-first. Confirm by parsing
        // overwrittenAtEpochMs in each line.
        assertTrue("100" in lines[0], "first line has epoch 100; got: $lines")
        assertTrue("200" in lines[1], "middle line has epoch 200")
        assertTrue("300" in lines[2], "last line has epoch 300")
    }

    @Test fun appendUsesCompactJsonNotPretty() {
        // Pin: kdoc explicitly: "JSONL encoding uses
        // JsonConfig.default (single-line compact) NOT the
        // store's pretty-print." Drift to pretty-print would
        // break per-line reading (newlines in the body would
        // count as JSONL row breaks).
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("compact-test")
        appendSourceNodeHistoryFile(
            fs,
            bundlePath,
            nodeId,
            BodyRevision(
                body = buildJsonObject {
                    put("multi-field", JsonPrimitive("v"))
                    put("another", JsonPrimitive(42))
                },
                overwrittenAtEpochMs = 5L,
            ),
        )
        val file = bundlePath.resolve(FileProjectStore.SOURCE_HISTORY_DIR).resolve("compact-test.jsonl")
        val text = fs.read(file) { readUtf8() }
        // Pin: exactly 1 newline (the trailing one). Pretty
        // JSON would inject \n between fields.
        assertEquals(
            1,
            text.count { it == '\n' },
            "compact JSON: only the trailing \\n; got: '$text'",
        )
    }

    @Test fun appendIsNamespacedPerNodeId() {
        // Pin: each nodeId gets its own file. Drift to a single
        // shared file would cross-contaminate node histories.
        val fs = FakeFileSystem()
        appendSourceNodeHistoryFile(fs, bundlePath, SourceNodeId("node-a"), bodyRevision("v", "for-a", 1L))
        appendSourceNodeHistoryFile(fs, bundlePath, SourceNodeId("node-b"), bodyRevision("v", "for-b", 2L))

        val historyDir = bundlePath.resolve(FileProjectStore.SOURCE_HISTORY_DIR)
        assertTrue(fs.exists(historyDir.resolve("node-a.jsonl")), "node-a file present")
        assertTrue(fs.exists(historyDir.resolve("node-b.jsonl")), "node-b file present")

        val aRevs = listSourceNodeHistoryFile(fs, bundlePath, SourceNodeId("node-a"), 100)
        val bRevs = listSourceNodeHistoryFile(fs, bundlePath, SourceNodeId("node-b"), 100)
        assertEquals(1, aRevs.size, "node-a has only its own revision")
        assertEquals(1, bRevs.size, "node-b has only its own revision")
        assertTrue(
            "for-a" in aRevs.single().body.toString(),
            "node-a body untouched; got: ${aRevs.single().body}",
        )
    }

    // ── list: missing-file → empty ───────────────────────────────

    @Test fun listMissingFileReturnsEmptyListNotException() {
        // The marquee never-throws pin: a node that's never
        // had its body updated has no JSONL file. Returning
        // empty list is the kdoc-documented contract; throwing
        // would corrupt the source_query(select=history)
        // narrative for fresh nodes.
        val fs = FakeFileSystem()
        // Don't plant any file.
        val rows = listSourceNodeHistoryFile(
            fs,
            bundlePath,
            SourceNodeId("never-edited"),
            limit = 100,
        )
        assertEquals(emptyList(), rows)
    }

    @Test fun listMissingDirReturnsEmptyListNotException() {
        // Pin: even the source-history DIR doesn't need to
        // exist. The function checks `fs.exists(file)` and
        // bails out before trying to read.
        val fs = FakeFileSystem()
        // Plant the bundle dir but NOT source-history.
        fs.createDirectories(bundlePath)
        val rows = listSourceNodeHistoryFile(
            fs,
            bundlePath,
            SourceNodeId("anywhere"),
            limit = 100,
        )
        assertEquals(emptyList(), rows)
    }

    // ── list: newest-first sort ─────────────────────────────────

    @Test fun listReturnsRevisionsNewestFirstFromOldestFirstStorage() {
        // The marquee newest-first pin: storage is oldest-
        // first (append order), but the read API reverses so
        // callers don't have to reason about it. Plant 3
        // revisions, read back; first row must be the most-
        // recently appended.
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("ordered")
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "old", 100L))
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "mid", 200L))
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "new", 300L))

        val rows = listSourceNodeHistoryFile(fs, bundlePath, nodeId, limit = 100)
        // Pin: newest-first.
        assertEquals(
            listOf(300L, 200L, 100L),
            rows.map { it.overwrittenAtEpochMs },
            "newest first; got: $rows",
        )
    }

    @Test fun listLimitTrimsToTopN() {
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("paginated")
        repeat(5) { i ->
            appendSourceNodeHistoryFile(
                fs,
                bundlePath,
                nodeId,
                bodyRevision("v", "rev-$i", i.toLong() * 1000L),
            )
        }
        // Pin: limit=2 returns the 2 newest. With i=0..4, the
        // newest is i=4 (epoch 4000), then i=3.
        val rows = listSourceNodeHistoryFile(fs, bundlePath, nodeId, limit = 2)
        assertEquals(2, rows.size)
        assertEquals(listOf(4_000L, 3_000L), rows.map { it.overwrittenAtEpochMs })
    }

    @Test fun listLimitGreaterThanAvailableReturnsAllAvailable() {
        // Pin: limit > stored count is not an error — return
        // all stored, in newest-first order. Drift to "throw
        // when limit too big" would crash the agent for
        // small histories.
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("small-history")
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "only", 50L))

        val rows = listSourceNodeHistoryFile(fs, bundlePath, nodeId, limit = 100)
        assertEquals(1, rows.size)
        assertEquals(50L, rows.single().overwrittenAtEpochMs)
    }

    @Test fun listLimitZeroReturnsEmpty() {
        // Pin: limit=0 → take(0) → empty. Drift to "treat 0
        // as unlimited" would silently flood callers that
        // intentionally asked for nothing.
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("with-history")
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "x", 1L))
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "y", 2L))

        val rows = listSourceNodeHistoryFile(fs, bundlePath, nodeId, limit = 0)
        assertEquals(emptyList(), rows)
    }

    // ── round-trip preserves body content ───────────────────────

    @Test fun roundTripPreservesBodyJsonStructure() {
        // Pin: encode → store → decode preserves the
        // JsonObject body exactly. The body field is a
        // JsonElement (per kdoc, "permissive typing"). A
        // regression that flattened the body to a string
        // would lose nested structure.
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("nested")
        val rich = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Mei"),
                "age" to JsonPrimitive(28),
            ),
        )
        appendSourceNodeHistoryFile(
            fs,
            bundlePath,
            nodeId,
            BodyRevision(body = rich, overwrittenAtEpochMs = 999L),
        )

        val rows = listSourceNodeHistoryFile(fs, bundlePath, nodeId, limit = 1)
        val readBack = rows.single()
        assertEquals(rich, readBack.body, "body JsonElement preserved exactly")
        assertEquals(999L, readBack.overwrittenAtEpochMs)
    }

    @Test fun blankLinesInJsonlAreIgnoredOnRead() {
        // Pin: `text.lineSequence().filter { it.isNotBlank() }`
        // — empty lines (e.g. trailing whitespace, file ended
        // with double-newline) don't get parsed as bogus
        // empty revisions. Drift to including blank lines
        // would throw on JSON decode.
        val fs = FakeFileSystem()
        val nodeId = SourceNodeId("blanks")
        appendSourceNodeHistoryFile(fs, bundlePath, nodeId, bodyRevision("v", "x", 1L))
        // Tamper with file: append a blank line + another
        // entry + extra blank.
        val file = bundlePath
            .resolve(FileProjectStore.SOURCE_HISTORY_DIR)
            .resolve("blanks.jsonl")
        val withBlanks = fs.read(file) { readUtf8() } + "\n\n" + """{"body":{"v":"y"},"overwrittenAtEpochMs":2}""" + "\n\n"
        fs.write(file) { writeUtf8(withBlanks) }

        val rows = listSourceNodeHistoryFile(fs, bundlePath, nodeId, limit = 100)
        // Pin: 2 valid rows, blanks dropped. No exception.
        assertEquals(2, rows.size, "blanks ignored; got: $rows")
        // Newest first.
        assertEquals(2L, rows[0].overwrittenAtEpochMs)
        assertEquals(1L, rows[1].overwrittenAtEpochMs)
    }
}
