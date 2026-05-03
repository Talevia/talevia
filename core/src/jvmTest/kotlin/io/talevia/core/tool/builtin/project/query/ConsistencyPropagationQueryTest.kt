package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runConsistencyPropagationQuery] —
 * `project_query(select=consistency_propagation)`. The VISION §5.5
 * "did my character_ref really influence shot-3?" audit. Cycle 127
 * audit: 141 LOC, **zero** transitive test references.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Keyword extraction is top-level string values only,
 *    de-duped, blank-skipped.** Nested objects/arrays are
 *    ignored. A regression deeply traversing would either
 *    inflate keyword lists with nested structural strings (UI
 *    confusion) or include irrelevant keys; a regression
 *    keeping blanks would surface "" matches that always pass.
 *
 * 2. **Match is case-insensitive substring on
 *    `baseInputs.prompt`.** Both keyword and prompt are
 *    `.lowercase()`d before `in`-check. A regression making
 *    the match case-sensitive would silently drop "Mei" matches
 *    against "mei walks down the alley".
 *
 * 3. **Tri-state `aigcEntryFound` reporting.** Per kdoc:
 *    "Non-AIGC clips (text without asset), and clips whose
 *    asset lacks a lockfile entry, are still reported with
 *    `aigcEntryFound=false` so the auditor sees the full
 *    surface." A regression filtering out non-AIGC clips would
 *    silently hide propagation gaps the user needs to see.
 */
class ConsistencyPropagationQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, assetId: String, binding: Set<SourceNodeId>) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        sourceBinding = binding,
    )

    private fun textClip(id: String, binding: Set<SourceNodeId>) = Clip.Text(
        id = ClipId(id),
        timeRange = timeRange,
        text = "subtitle",
        sourceBinding = binding,
    )

    private fun lockEntry(
        assetId: String,
        prompt: String? = null,
    ) = LockfileEntry(
        inputHash = "h-$assetId",
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "gpt-image-1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
        baseInputs = if (prompt == null) {
            JsonObject(emptyMap())
        } else {
            buildJsonObject { put("prompt", prompt) }
        },
        originatingMessageId = MessageId("m"),
    )

    private fun project(
        nodes: List<SourceNode>,
        clips: List<Clip> = emptyList(),
        entries: List<LockfileEntry> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes, revision = 5),
            lockfile = EagerLockfile(entries = entries),
        )
    }

    private fun input(sourceNodeId: String?) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_CONSISTENCY_PROPAGATION,
        sourceNodeId = sourceNodeId,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<ConsistencyPropagationRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ConsistencyPropagationRow.serializer()),
            out.rows,
        )

    // ── input validation ──────────────────────────────────────────

    @Test fun missingSourceNodeIdErrorsLoud() {
        val ex = assertFailsWith<IllegalStateException> {
            runConsistencyPropagationQuery(project(emptyList()), input(null), 100, 0)
        }
        assertTrue("requires the 'sourceNodeId'" in (ex.message ?: ""))
    }

    @Test fun unknownSourceNodeIdErrorsWithRecoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            runConsistencyPropagationQuery(project(emptyList()), input("ghost"), 100, 0)
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "got: $msg")
        assertTrue("not found" in msg, "got: $msg")
        assertTrue("source_query(select=nodes)" in msg, "recovery; got: $msg")
    }

    // ── keyword extraction ───────────────────────────────────────

    @Test fun keywordsExtractTopLevelStringValuesInOrder() {
        // Per kdoc: "Flatten top-level string values from body
        // into a de-duplicated, order-preserving list."
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject {
                put("name", "Mei")
                put("visualDescription", "tall girl with red hair")
                put("priority", 5) // non-string skipped
            },
        )
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(node)),
                input("char"),
                100,
                0,
            ).data,
        )
        // No bound clips → still get summary, but rows empty.
        // Pin keywords via summary text inspection.
        val out = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("char"),
            100,
            0,
        ).outputForLlm
        assertTrue("Mei" in out, "name keyword; got: $out")
        assertTrue("tall girl with red hair" in out, "visualDescription keyword; got: $out")
        // Non-string field should NOT surface.
        assertTrue("priority" !in out, "non-string field excluded; got: $out")
    }

    @Test fun keywordsSkipBlankStrings() {
        // Pin: blank strings dropped. A regression keeping
        // them would let "" match every prompt (always-pass
        // false-positive).
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject {
                put("name", "")
                put("description", "  ") // whitespace also blank
                put("visualDescription", "real keyword")
            },
        )
        val out = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("char"),
            100,
            0,
        ).outputForLlm
        assertTrue("real keyword" in out)
        // Pin: only the real keyword surfaces (separator after).
        // Use the summary's keyword section to verify.
        assertTrue(
            "keywords: real keyword" in out,
            "blank entries dropped; got: $out",
        )
    }

    @Test fun keywordsDedupeSameStringValue() {
        // Pin: linkedSetOf preserves order + dedupes. Two
        // top-level fields with the same string → one keyword.
        val node = SourceNode.create(
            id = SourceNodeId("c"),
            kind = "k",
            body = buildJsonObject {
                put("a", "Mei")
                put("b", "Mei")
            },
        )
        val out = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("c"),
            100,
            0,
        ).outputForLlm
        // "Mei" appears once in the keyword list.
        val keywordSection = out.substringAfter("keywords: ").substringBefore(".")
        assertEquals("Mei", keywordSection, "dedup; got section: '$keywordSection'")
    }

    @Test fun keywordsEmptyForNonObjectBody() {
        // Pin defensive: JsonPrimitive body → empty keywords.
        val node = SourceNode.create(
            id = SourceNodeId("c"),
            kind = "k",
            body = JsonPrimitive("just-a-string"),
        )
        val out = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("c"),
            100,
            0,
        ).outputForLlm
        assertTrue("keywords: none" in out, "empty marker; got: $out")
    }

    @Test fun keywordsSkipNestedObjectsAndArrays() {
        // Pin kdoc commitment: "Nested objects / arrays are
        // skipped for now". A regression deep-traversing would
        // surface object structure as keywords.
        val node = SourceNode.create(
            id = SourceNodeId("c"),
            kind = "k",
            body = buildJsonObject {
                put("name", "Mei")
                putJsonObject("nested") { put("deep", "should-not-surface") }
                putJsonArray("colors") { add(JsonPrimitive("#fff")) }
            },
        )
        val out = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("c"),
            100,
            0,
        ).outputForLlm
        assertTrue("Mei" in out, "top-level surfaces; got: $out")
        assertTrue("should-not-surface" !in out, "nested NOT surfaced; got: $out")
        assertTrue("#fff" !in out, "array element NOT surfaced; got: $out")
    }

    // ── tri-state aigcEntryFound ──────────────────────────────────

    @Test fun clipWithoutAigcEntryReportsAigcEntryFoundFalse() {
        // Pin: clip with sourceBinding but no lockfile entry —
        // aigcEntryFound=false, lockfileInputHash=null,
        // aigcToolId=null, matched empty.
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val clip = videoClip("c1", "no-entry-asset", setOf(SourceNodeId("char")))
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(node), listOf(clip)),
                input("char"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(false, row.aigcEntryFound, "no entry → false")
        assertEquals(null, row.lockfileInputHash)
        assertEquals(null, row.aigcToolId)
        assertEquals(emptyList(), row.keywordsMatchedInPrompt, "no entry → no match")
    }

    @Test fun textClipWithBindingButNoAssetReportsAigcEntryFoundFalseWithNullAssetId() {
        // Pin: text clips have no assetId in the domain model;
        // they still report (aigcEntryFound=false, assetId=null)
        // so the auditor sees the full bound surface.
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val text = textClip("t1", setOf(SourceNodeId("char")))
        val tracks = listOf(Track.Subtitle(TrackId("st"), listOf(text)))
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = listOf(node)),
        )
        val rows = decodeRows(
            runConsistencyPropagationQuery(proj, input("char"), 100, 0).data,
        )
        val row = rows.single()
        assertEquals("t1", row.clipId)
        assertEquals(null, row.assetId, "text clip has no assetId")
        assertEquals(false, row.aigcEntryFound)
    }

    // ── prompt matching: case-insensitive substring ──────────────

    @Test fun promptContainingKeywordSubstringMatchesCaseInsensitive() {
        // Marquee pin: lowercased substring match.
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("char")))
        val entry = lockEntry("asset-1", prompt = "MEI walks down the alley")
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(node), listOf(clip), listOf(entry)),
                input("char"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(true, row.aigcEntryFound)
        assertEquals(listOf("Mei"), row.keywordsMatchedInPrompt, "case-insensitive match")
        assertEquals(true, row.promptContainsKeywords)
    }

    @Test fun promptWithoutKeywordReportsEmptyMatchedAndFalseFlag() {
        // Pin: keyword absent from prompt → matched empty,
        // promptContainsKeywords=false. The "binding existed but
        // didn't propagate" case the audit is designed to surface.
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("char")))
        val entry = lockEntry("asset-1", prompt = "a generic landscape")
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(node), listOf(clip), listOf(entry)),
                input("char"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(true, row.aigcEntryFound)
        assertEquals(emptyList(), row.keywordsMatchedInPrompt)
        assertEquals(false, row.promptContainsKeywords)
    }

    @Test fun lockfileEntryWithoutPromptFieldReportsEmptyMatched() {
        // Pin: entry with no `baseInputs.prompt` → matched empty
        // (NOT crash). The defensive null-prompt path.
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("char")))
        val entry = lockEntry("asset-1", prompt = null) // no prompt field
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(node), listOf(clip), listOf(entry)),
                input("char"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        // Entry is found, but no prompt → no keyword match possible.
        assertEquals(true, row.aigcEntryFound)
        assertEquals(emptyList(), row.keywordsMatchedInPrompt)
    }

    @Test fun multipleKeywordsMatchReturnsAllPresentSubset() {
        // Pin: when N keywords appear in prompt, all N surface
        // in keywordsMatchedInPrompt. A regression first-match-
        // only would silently lose audit coverage.
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject {
                put("name", "Mei")
                put("hairColor", "red")
                put("attire", "armor")
            },
        )
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("char")))
        val entry = lockEntry(
            "asset-1",
            prompt = "Mei wearing red armor in the courtyard",
        )
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(node), listOf(clip), listOf(entry)),
                input("char"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(setOf("Mei", "red", "armor"), row.keywordsMatchedInPrompt.toSet())
        assertEquals(true, row.promptContainsKeywords)
    }

    @Test fun lockfileEntryFoundFlagsAreSetEvenWithoutMatches() {
        // Pin: aigcEntryFound + lockfileInputHash + aigcToolId
        // populate from the entry regardless of keyword match.
        // Match negativity affects only matched + flag.
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("char")))
        val entry = lockEntry("asset-1", prompt = "no match here")
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(node), listOf(clip), listOf(entry)),
                input("char"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(true, row.aigcEntryFound)
        assertEquals("h-asset-1", row.lockfileInputHash)
        assertEquals("generate_image", row.aigcToolId)
        assertEquals(false, row.promptContainsKeywords)
    }

    // ── direct vs transitive binding round-trip ──────────────────

    @Test fun directlyBoundFlagAndBoundViaRoundTripFromClipsBoundTo() {
        // a → b. Clip binds to b. Query for a: clipsBoundTo(a)
        // returns transitive binding; row carries directly=false +
        // boundVia=[b].
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = buildJsonObject { put("name", "A") },
        )
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("b")))
        val rows = decodeRows(
            runConsistencyPropagationQuery(
                project(listOf(a, b), listOf(clip)),
                input("a"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(false, row.directlyBound, "transitive")
        assertEquals(listOf("b"), row.boundVia)
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsRowsButTotalReflectsAllReports() {
        val node = SourceNode.create(
            id = SourceNodeId("char"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val clips = (1..5).map { videoClip("c$it", "a$it", setOf(SourceNodeId("char"))) }
        val result = runConsistencyPropagationQuery(
            project(listOf(node), clips),
            input("char"),
            2,
            0,
        )
        assertEquals(2, decodeRows(result.data).size, "page = limit")
        assertEquals(5, result.data.total, "total = all reports")
    }

    // ── outputForLlm summary format ──────────────────────────────

    @Test fun summaryFormatIncludesNodeIdKindKeywordsAndCounts() {
        val node = SourceNode.create(
            id = SourceNodeId("char-1"),
            kind = "narrative.character",
            body = buildJsonObject { put("name", "Mei") },
        )
        val clip1 = videoClip("c1", "a1", setOf(SourceNodeId("char-1")))
        val clip2 = videoClip("c2", "a2", setOf(SourceNodeId("char-1")))
        val entry = lockEntry("a1", prompt = "Mei walks")
        val out = runConsistencyPropagationQuery(
            project(listOf(node), listOf(clip1, clip2), listOf(entry)),
            input("char-1"),
            100,
            0,
        ).outputForLlm
        // Pin format: "Source node X (kind) keywords: K1, K2.
        // N bound clip(s), M with AIGC lockfile entry, P with
        // prompt containing ≥1 keyword."
        assertTrue("Source node char-1" in out, "node id; got: $out")
        assertTrue("(narrative.character)" in out, "kind; got: $out")
        assertTrue("keywords: Mei" in out, "keywords; got: $out")
        assertTrue("2 bound clip(s)" in out, "bound count; got: $out")
        assertTrue("1 with AIGC lockfile entry" in out, "covered count; got: $out")
        assertTrue("1 with prompt containing ≥1 keyword" in out, "propagated count; got: $out")
    }

    @Test fun summaryShowsKeywordsNoneWhenBodyEmpty() {
        // Pin: empty body → "keywords: none" sentinel.
        val node = SourceNode.create(
            id = SourceNodeId("c"),
            kind = "k",
            body = JsonObject(emptyMap()),
        )
        val out = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("c"),
            100,
            0,
        ).outputForLlm
        assertTrue("keywords: none" in out, "got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val node = SourceNode.create(
            id = SourceNodeId("c"),
            kind = "k",
            body = buildJsonObject { put("name", "x") },
        )
        val result = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("c"),
            100,
            0,
        )
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_CONSISTENCY_PROPAGATION, result.data.select)
    }

    @Test fun titleIncludesNodeId() {
        val node = SourceNode.create(
            id = SourceNodeId("char-1"),
            kind = "k",
            body = buildJsonObject { put("name", "Mei") },
        )
        val result = runConsistencyPropagationQuery(
            project(listOf(node)),
            input("char-1"),
            100,
            0,
        )
        assertTrue(
            "consistency_propagation char-1" in (result.title ?: ""),
            "title; got: ${result.title}",
        )
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
    key: String,
    builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    put(key, kotlinx.serialization.json.buildJsonObject(builder))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    builder: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
) {
    put(key, kotlinx.serialization.json.buildJsonArray(builder))
}
