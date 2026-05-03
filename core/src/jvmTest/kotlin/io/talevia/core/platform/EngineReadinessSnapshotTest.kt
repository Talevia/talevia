package io.talevia.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [buildEngineReadinessSnapshot] —
 * `core/platform/EngineReadinessSnapshot.kt`. The
 * compose-time builder feeding
 * `provider_query(select=engine_readiness)`. Each container
 * passes the engine slot it built (or null if absent); the
 * helper maps each into a row tagged with the canonical
 * (provider, env-var) it requires. Cycle 160 audit: 71
 * LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **All 8 engine kinds always present (informative
 *    zeros).** A container that wires zero engines still
 *    surfaces 8 rows — `wired = false` + `missingEnvVar`
 *    populated for each. Drift to "skip null engines"
 *    would let the agent see a partial readiness panel
 *    that hides which slots need keys.
 *
 * 2. **video_gen has a special two-provider path.** When
 *    wired, label with the actual `videoGen.providerId`
 *    (sora vs seedance); when unwired, list both providers
 *    in `"openai|volcano-seedance"` and BOTH env-var
 *    options in `"ARK_API_KEY or OPENAI_API_KEY"`. Drift
 *    to single-provider would silently lock users into
 *    one path even when their key is for the other.
 *
 * 3. **Stable (engine, provider, env-var) mapping.** This
 *    is the cross-container canonical assignment — any
 *    container can produce this snapshot and the agent
 *    sees the same provider/env-var mapping. A drift
 *    here (e.g. tts switching from openai to gemini) would
 *    let an agent on one container see different setup
 *    advice from the same agent on another container.
 */
class EngineReadinessSnapshotTest {

    // Stub engines — only `providerId` is read by the
    // helper for video_gen; other engines are non-null
    // sentinels.

    private class StubAsr : AsrEngine {
        override val providerId: String = "openai"
        override suspend fun transcribe(request: AsrRequest): AsrResult = error("not used")
    }

    private class StubImageGen : ImageGenEngine {
        override val providerId: String = "openai"
        override suspend fun generate(request: ImageGenRequest): ImageGenResult = error("not used")
    }

    private class StubMusicGen : MusicGenEngine {
        override val providerId: String = "replicate"
        override suspend fun generate(request: MusicGenRequest): MusicGenResult = error("not used")
    }

    private class StubTts : TtsEngine {
        override val providerId: String = "openai"
        override suspend fun synthesize(request: TtsRequest): TtsResult = error("not used")
    }

    private class StubVision : VisionEngine {
        override val providerId: String = "openai"
        override suspend fun describe(request: VisionRequest): VisionResult = error("not used")
    }

    private class StubUpscale : UpscaleEngine {
        override val providerId: String = "replicate"
        override suspend fun upscale(request: UpscaleRequest): UpscaleResult = error("not used")
    }

    private class StubSearch : SearchEngine {
        override val providerId: String = "tavily"
        override suspend fun search(query: String, maxResults: Int): SearchResults = error("not used")
    }

    private class StubVideoGen(override val providerId: String) : VideoGenEngine {
        override suspend fun generate(request: VideoGenRequest): VideoGenResult = error("not used")
    }

    // ── all unwired (zero engines) ──────────────────────────────

    @Test fun allUnwiredProducesEightRowsAllWiredFalse() {
        // The marquee informative-zeros pin: a container
        // with NO AIGC engines wired still surfaces 8 rows.
        // Drift to skip-null would hide the slots needing
        // keys, leaving the agent unsure which env vars to
        // suggest.
        val rows = buildEngineReadinessSnapshot()
        assertEquals(8, rows.size, "all 8 engine kinds present")
        assertTrue(
            rows.all { !it.wired },
            "all wired=false; got: ${rows.map { it.engineKind to it.wired }}",
        )
        // All rows have missingEnvVar populated.
        assertTrue(rows.all { it.missingEnvVar != null })
    }

    @Test fun unwiredRowsCarryCanonicalProviderEnvVarMapping() {
        // The marquee stable-mapping pin: each engine kind
        // maps to its canonical provider + env var. Drift to
        // a different provider (e.g. tts → gemini) would
        // silently re-route the agent's setup advice.
        val rows = buildEngineReadinessSnapshot().associateBy { it.engineKind }

        // image_gen → openai / OPENAI_API_KEY
        assertEquals("openai", rows.getValue("image_gen").providerId)
        assertEquals("OPENAI_API_KEY", rows.getValue("image_gen").missingEnvVar)
        // tts → openai
        assertEquals("openai", rows.getValue("tts").providerId)
        assertEquals("OPENAI_API_KEY", rows.getValue("tts").missingEnvVar)
        // asr → openai
        assertEquals("openai", rows.getValue("asr").providerId)
        assertEquals("OPENAI_API_KEY", rows.getValue("asr").missingEnvVar)
        // vision → openai
        assertEquals("openai", rows.getValue("vision").providerId)
        assertEquals("OPENAI_API_KEY", rows.getValue("vision").missingEnvVar)
        // music_gen → replicate
        assertEquals("replicate", rows.getValue("music_gen").providerId)
        assertEquals("REPLICATE_API_TOKEN", rows.getValue("music_gen").missingEnvVar)
        // upscale → replicate
        assertEquals("replicate", rows.getValue("upscale").providerId)
        assertEquals("REPLICATE_API_TOKEN", rows.getValue("upscale").missingEnvVar)
        // search → tavily
        assertEquals("tavily", rows.getValue("search").providerId)
        assertEquals("TAVILY_API_KEY", rows.getValue("search").missingEnvVar)
    }

    // ── video_gen two-provider path ─────────────────────────────

    @Test fun videoGenUnwiredListsBothProvidersAndBothEnvVars() {
        // Marquee dual-provider unwired pin: the only engine
        // with two prod impls. When unwired, the
        // missingEnvVar must list BOTH options ("setting
        // either unlocks the slot") and providerId must show
        // both candidate names.
        val row = buildEngineReadinessSnapshot()
            .single { it.engineKind == "video_gen" }
        assertEquals(false, row.wired)
        assertEquals("openai|volcano-seedance", row.providerId)
        assertEquals("ARK_API_KEY or OPENAI_API_KEY", row.missingEnvVar)
    }

    @Test fun videoGenWiredLabelsWithActualProviderNotPlaceholder() {
        // The marquee wired-video pin: when actually wired,
        // the row tags with the SPECIFIC providerId picked
        // (so the agent knows which pricing / capabilities
        // to expect). Drift to leaving the placeholder
        // "openai|volcano-seedance" even when wired would
        // confuse the agent about the active backend.
        val rowWithSora = buildEngineReadinessSnapshot(
            videoGen = StubVideoGen("openai"),
        ).single { it.engineKind == "video_gen" }
        assertEquals(true, rowWithSora.wired)
        assertEquals("openai", rowWithSora.providerId, "actual providerId, not placeholder")
        assertNull(rowWithSora.missingEnvVar, "wired → no missing env var")

        val rowWithSeedance = buildEngineReadinessSnapshot(
            videoGen = StubVideoGen("volcano-seedance"),
        ).single { it.engineKind == "video_gen" }
        assertEquals(true, rowWithSeedance.wired)
        assertEquals("volcano-seedance", rowWithSeedance.providerId)
    }

    // ── single-engine wired ─────────────────────────────────────

    @Test fun wiringSingleEngineFlipsOnlyThatRow() {
        // Pin: passing one engine flips that one engineKind's
        // row to wired=true with missingEnvVar=null; all
        // other rows stay unwired with their canonical
        // env-vars.
        val rows = buildEngineReadinessSnapshot(imageGen = StubImageGen())
        assertEquals(8, rows.size, "still 8 rows")

        val image = rows.single { it.engineKind == "image_gen" }
        assertEquals(true, image.wired)
        assertNull(image.missingEnvVar)
        // The 6 single-provider unwired siblings stay unwired.
        // (video_gen is the dual-provider exception, also unwired.)
        assertEquals(
            7,
            rows.count { !it.wired },
            "7 of 8 still unwired",
        )
    }

    // ── all engines wired ───────────────────────────────────────

    @Test fun allWiredProducesEightRowsAllWiredTrueNoMissingEnvVars() {
        val rows = buildEngineReadinessSnapshot(
            imageGen = StubImageGen(),
            videoGen = StubVideoGen("openai"),
            musicGen = StubMusicGen(),
            tts = StubTts(),
            asr = StubAsr(),
            vision = StubVision(),
            upscale = StubUpscale(),
            search = StubSearch(),
        )
        assertEquals(8, rows.size)
        assertTrue(rows.all { it.wired }, "all wired")
        assertTrue(rows.all { it.missingEnvVar == null }, "no missing env vars on fully-wired")
    }

    // ── ordering ────────────────────────────────────────────────

    @Test fun rowsAppearInDocumentedOrder() {
        // Pin: the order in the listOf(...) at end of the
        // function: image_gen, video_gen, tts, asr, vision,
        // music_gen, upscale, search. UI panels render in
        // this order — drift would shuffle the panel layout.
        val expected = listOf(
            "image_gen",
            "video_gen",
            "tts",
            "asr",
            "vision",
            "music_gen",
            "upscale",
            "search",
        )
        val actual = buildEngineReadinessSnapshot().map { it.engineKind }
        assertEquals(expected, actual)
    }

    // ── partial wiring permutations ────────────────────────────

    @Test fun mixedPartialWiringIndependentlyTagged() {
        // Pin: each slot is independently checked. Wiring 3
        // of 8 produces 3 wired + 5 unwired with their
        // canonical env-vars intact.
        val rows = buildEngineReadinessSnapshot(
            imageGen = StubImageGen(),
            tts = StubTts(),
            search = StubSearch(),
        ).associateBy { it.engineKind }

        assertEquals(true, rows.getValue("image_gen").wired)
        assertEquals(true, rows.getValue("tts").wired)
        assertEquals(true, rows.getValue("search").wired)

        // Others unwired with canonical env-vars.
        assertEquals(false, rows.getValue("video_gen").wired)
        assertEquals("ARK_API_KEY or OPENAI_API_KEY", rows.getValue("video_gen").missingEnvVar)
        assertEquals(false, rows.getValue("music_gen").wired)
        assertEquals("REPLICATE_API_TOKEN", rows.getValue("music_gen").missingEnvVar)
        assertEquals(false, rows.getValue("vision").wired)
        assertEquals("OPENAI_API_KEY", rows.getValue("vision").missingEnvVar)
    }
}
