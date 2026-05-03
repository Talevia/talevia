package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [runEngineReadinessQuery] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/provider/query/EngineReadinessQuery.kt`.
 * Cycle 245 audit: only indirect coverage exists
 * (`ProviderQueryEngineReadinessTest` exercises the function via
 * `ProviderQueryTool.execute(select="engine_readiness")` but
 * doesn't pin the user-visible summary string formatting or
 * pluralization).
 *
 * Same audit-pattern fallback as cycles 207-244.
 *
 * `runEngineReadinessQuery(snapshot)` is a pure function that
 * compresses an engine-readiness snapshot into a `ToolResult` whose
 * `outputForLlm` field becomes the **string the LLM reads** to
 * decide whether AIGC tools are dispatchable. Drift in any of the
 * three summary branches silently changes what the model sees:
 *
 *   - snapshot==null      → "not wired in this rig" note
 *   - snapshot.isEmpty()  → "empty — no AIGC engines" note
 *   - snapshot non-empty  → "X/Y wired" + per-engine flags
 *
 * The third branch carries the canonical `engine_kind=✓` /
 * `engine_kind=✗(ENVVAR)` format. Drift to drop the env-var
 * suggestion would silently lose the agent's recovery hint.
 *
 * Pins three correctness contracts:
 *
 *  1. **Three-branch summary semantics**. Each branch's
 *     distinguishing substring (`not wired in this rig` /
 *     `empty` + `no AIGC engines` / `X/Y wired`) is pinned to
 *     surface drift between the three states the LLM relies on
 *     to disambiguate "tool not yet hooked up" vs "no engines
 *     installed" vs "some engines installed".
 *
 *  2. **Per-engine summary format `name=✓` / `name=✗(envvar)`.**
 *     The wired flag uses `✓` (U+2713); the unwired flag uses
 *     `✗` (U+2717) AND surfaces the env var. Drift in the glyph
 *     or the envvar parens would silently change the LLM's
 *     diagnosis hint.
 *
 *  3. **Title pluralization**. `0` / `1 engine` (singular) /
 *     `N engines` (plural). Drift to "always 'engines'" surfaces
 *     here as `1 engines`. (Hardly load-bearing in absolute terms
 *     but correctness invariants are correctness invariants — the
 *     LLM context is small enough that a typo is visible.)
 *
 * Plus structural pins:
 *   - `total == returned == rows.size` (drift to "total = snapshot.size,
 *     returned = filtered.size" would let "engine_readiness" lie
 *     about the row count).
 *   - `select` field is `SELECT_ENGINE_READINESS` (the canonical
 *     constant; drift would mismatch downstream UIs that branch
 *     on the select id).
 *   - `rows` is a JsonArray with N entries matching the input
 *     snapshot, sorted by `engineKind` ascending (stable diffing
 *     across re-runs).
 *   - Empty snapshot still produces empty `JsonArray`, NOT null
 *     (drift to null would crash JSON consumers expecting an
 *     array).
 */
class EngineReadinessQueryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun rowsFrom(out: ProviderQueryTool.Output): List<EngineReadinessRow> =
        json.decodeFromJsonElement(
            ListSerializer(EngineReadinessRow.serializer()),
            out.rows,
        )

    // ── 1. Three-branch summary semantics ───────────────────

    @Test fun nullSnapshotProducesNotWiredNote() {
        // Marquee branch-A pin: when snapshot is null
        // (composition root didn't pass any), the LLM gets the
        // "not wired in this rig" note. Drift to "empty rig" would
        // confuse the LLM about whether the container itself is
        // mis-configured (snapshot==null) vs is wired but empty
        // (snapshot==[]).
        val result = runEngineReadinessQuery(snapshot = null)
        assertTrue(
            "not wired in this rig" in result.outputForLlm,
            "null snapshot MUST surface the 'not wired in this rig' phrase; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Production containers always wire it" in result.outputForLlm,
            "null-snapshot note MUST cite the production-vs-test distinction; got: ${result.outputForLlm}",
        )
    }

    @Test fun emptySnapshotProducesNoAigcEnginesNote() {
        // Marquee branch-B pin: an EMPTY (non-null) snapshot is
        // distinct from null — the container actively reported
        // "no engines". The LLM should NOT recover by setting an
        // env var (no engine to wire); instead it surfaces "no
        // AIGC engines".
        val result = runEngineReadinessQuery(snapshot = emptyList())
        assertTrue(
            "empty" in result.outputForLlm,
            "empty snapshot MUST surface 'empty'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "no AIGC engines" in result.outputForLlm,
            "empty snapshot MUST cite 'no AIGC engines'; got: ${result.outputForLlm}",
        )
        // Branch separation: empty MUST NOT carry the null-branch's
        // "not wired in this rig" phrase.
        assertTrue(
            "not wired in this rig" !in result.outputForLlm,
            "empty snapshot MUST NOT carry the null-branch's phrase",
        )
    }

    @Test fun nonEmptySnapshotProducesWiredCountSummary() {
        // Marquee branch-C pin: non-empty snapshot reports
        // `wired/total wired`. Drift to "all engines wired" /
        // "engine status summary" would lose the count-at-a-glance
        // signal the LLM uses to decide if it's worth retrying.
        val snapshot = listOf(
            EngineReadinessRow("image_gen", "openai", wired = true),
            EngineReadinessRow("music_gen", "replicate", wired = false, missingEnvVar = "REPLICATE_API_TOKEN"),
        )
        val result = runEngineReadinessQuery(snapshot)
        assertTrue(
            "1/2 wired" in result.outputForLlm,
            "non-empty MUST report 'wired/total wired'; got: ${result.outputForLlm}",
        )
    }

    // ── 2. Per-engine summary format ────────────────────────

    @Test fun wiredEngineFormatUsesCheckmarkGlyph() {
        // Marquee glyph pin: drift from `✓` (U+2713) to `[OK]`
        // / `Y` / etc. would silently change the LLM's parse
        // mental model. Pin the exact glyph so a refactor that
        // sweeps the codebase for non-ASCII chars surfaces here.
        val snapshot = listOf(
            EngineReadinessRow("image_gen", "openai", wired = true),
        )
        val result = runEngineReadinessQuery(snapshot)
        assertTrue(
            "image_gen=✓" in result.outputForLlm,
            "wired engine MUST use '✓' glyph and `kind=✓` format; got: ${result.outputForLlm}",
        )
    }

    @Test fun unwiredEngineFormatUsesCrossGlyphAndEnvVar() {
        // Marquee glyph + envvar pin: unwired engines display
        // `kind=✗(ENVVAR)` so the LLM knows the recovery action.
        // Drift to drop the envvar parens (just `✗`) silently
        // loses the recovery hint; drift to drop the `✗` glyph
        // breaks the parser.
        val snapshot = listOf(
            EngineReadinessRow(
                "music_gen",
                "replicate",
                wired = false,
                missingEnvVar = "REPLICATE_API_TOKEN",
            ),
        )
        val result = runEngineReadinessQuery(snapshot)
        assertTrue(
            "music_gen=✗(REPLICATE_API_TOKEN)" in result.outputForLlm,
            "unwired engine MUST use '✗(envvar)' format; got: ${result.outputForLlm}",
        )
    }

    @Test fun unwiredEngineWithoutEnvVarShowsQuestionMark() {
        // Pin: per the format `${it.missingEnvVar ?: "?"}`, an
        // unwired engine with NO known envvar (rare — would
        // mean the container is reporting unwired-but-with-no-
        // recovery) shows `?`. Drift to "" (empty parens) or
        // `null` (literal string) would leak internals into the
        // LLM message.
        val snapshot = listOf(
            EngineReadinessRow(
                "vision",
                "anthropic",
                wired = false,
                missingEnvVar = null,
            ),
        )
        val result = runEngineReadinessQuery(snapshot)
        assertTrue(
            "vision=✗(?)" in result.outputForLlm,
            "unwired+no-envvar MUST format as '✗(?)'; got: ${result.outputForLlm}",
        )
    }

    @Test fun multipleEnginesAreCommaSeparatedInSummary() {
        // Pin: rows are joined with `, `. Drift to newline / `;`
        // would silently change the LLM context's tokenization.
        val snapshot = listOf(
            EngineReadinessRow("image_gen", "openai", wired = true),
            EngineReadinessRow(
                "music_gen",
                "replicate",
                wired = false,
                missingEnvVar = "REPLICATE_API_TOKEN",
            ),
        )
        val result = runEngineReadinessQuery(snapshot)
        // Both `image_gen=✓` and `music_gen=✗(REPLICATE_API_TOKEN)`
        // appear, separated by ", " somewhere.
        assertTrue(
            "image_gen=✓" in result.outputForLlm &&
                "music_gen=✗(REPLICATE_API_TOKEN)" in result.outputForLlm,
            "both engines must appear in the summary",
        )
        assertTrue(
            "image_gen=✓, music_gen=✗" in result.outputForLlm ||
                "music_gen=✗(REPLICATE_API_TOKEN), image_gen=✓" in result.outputForLlm,
            "engines MUST be comma-space-separated in the summary; got: ${result.outputForLlm}",
        )
    }

    // ── 3. Title pluralization ──────────────────────────────

    @Test fun titlePluralizationForZeroEngines() {
        // Pin: zero engines → "0 engines" (plural). Drift to "0
        // engine" would surface here.
        val result = runEngineReadinessQuery(emptyList())
        assertTrue(
            "(0 engines)" in result.title,
            "zero engines MUST use plural; got: ${result.title}",
        )
    }

    @Test fun titlePluralizationForSingleEngine() {
        // Pin: 1 engine → "1 engine" (singular). Drift to "1
        // engines" surfaces here. The `if (rows.size == 1) ""
        // else "s"` arm is the load-bearing logic.
        val snapshot = listOf(
            EngineReadinessRow("image_gen", "openai", wired = true),
        )
        val result = runEngineReadinessQuery(snapshot)
        assertTrue(
            "(1 engine)" in result.title,
            "single engine MUST use singular 'engine' (NOT 'engines'); got: ${result.title}",
        )
        // Negative-evidence: the plural form must NOT appear.
        assertTrue(
            "(1 engines)" !in result.title,
            "single engine MUST NOT use plural; got: ${result.title}",
        )
    }

    @Test fun titlePluralizationForMultipleEngines() {
        val snapshot = listOf(
            EngineReadinessRow("image_gen", "openai", wired = true),
            EngineReadinessRow("video_gen", "openai", wired = true),
            EngineReadinessRow("music_gen", "replicate", wired = false),
        )
        val result = runEngineReadinessQuery(snapshot)
        assertTrue(
            "(3 engines)" in result.title,
            "multi-engine MUST use plural; got: ${result.title}",
        )
    }

    // ── 4. Output structure ─────────────────────────────────

    @Test fun selectFieldIsCanonicalEngineReadinessConstant() {
        // Pin: drift from SELECT_ENGINE_READINESS would mismatch
        // downstream UIs that branch on the select id.
        val result = runEngineReadinessQuery(emptyList())
        assertEquals(
            ProviderQueryTool.SELECT_ENGINE_READINESS,
            result.data.select,
            "select MUST be the canonical SELECT_ENGINE_READINESS constant",
        )
    }

    @Test fun totalEqualsReturnedEqualsRowCount() {
        // Pin: this select doesn't paginate, so total == returned
        // == snapshot size. Drift to "total != returned" would
        // confuse pagination consumers.
        val snapshot = listOf(
            EngineReadinessRow("image_gen", "openai", wired = true),
            EngineReadinessRow("music_gen", "replicate", wired = false),
            EngineReadinessRow("tts", "openai", wired = true),
        )
        val result = runEngineReadinessQuery(snapshot)
        assertEquals(3, result.data.total)
        assertEquals(3, result.data.returned)
    }

    @Test fun rowsAreSortedByEngineKindAscending() {
        // Marquee deterministic-output pin: rows are sorted by
        // engineKind ascending so re-runs produce the same row
        // order — load-bearing for stable diffing in agent
        // traces, golden-file tests, etc.
        val snapshot = listOf(
            EngineReadinessRow("video_gen", "openai", wired = true),
            EngineReadinessRow("asr", "openai", wired = true),
            EngineReadinessRow("music_gen", "replicate", wired = false),
            EngineReadinessRow("image_gen", "openai", wired = true),
        )
        val result = runEngineReadinessQuery(snapshot)
        val rows = rowsFrom(result.data)
        assertEquals(
            listOf("asr", "image_gen", "music_gen", "video_gen"),
            rows.map { it.engineKind },
            "rows MUST be sorted by engineKind ascending; got: ${rows.map { it.engineKind }}",
        )
    }

    @Test fun emptySnapshotProducesEmptyJsonArrayNotNull() {
        // Pin: drift to "use null when empty" would crash JSON
        // consumers that expect an array even for empty results.
        // The serializer's `ListSerializer + encodeToJsonElement`
        // path produces `[]` not `null` — pin so a future
        // refactor that uses `if (empty) null` surfaces here.
        // (`rows` is statically typed `JsonArray`; the size assert
        // is the load-bearing check.)
        val result = runEngineReadinessQuery(emptyList())
        assertEquals(0, result.data.rows.size, "empty snapshot MUST produce empty JsonArray")
    }

    @Test fun nullSnapshotAlsoProducesEmptyJsonArrayNotNull() {
        // Sister pin to the empty-snapshot case: null also funnels
        // through `(snapshot ?: emptyList())` and produces an
        // empty JsonArray. The branch-A note differs in the
        // outputForLlm text, but the data shape stays consistent.
        val result = runEngineReadinessQuery(snapshot = null)
        assertEquals(0, result.data.rows.size)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
    }
}
