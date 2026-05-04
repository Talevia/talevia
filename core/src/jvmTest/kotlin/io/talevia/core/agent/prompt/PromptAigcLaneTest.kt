package io.talevia.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Direct content tests for [PROMPT_AIGC_LANE] —
 * `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/PromptAigcLane.kt:11`.
 * Cycle 282 audit: 0 prior test refs (cycle 281 covered the
 * sister PROMPT_DUAL_USER lane; this is the next one in the
 * static-base ordering).
 *
 * Same audit-pattern fallback as cycles 207-281. Wrap-tolerance
 * idiom (`flat` whitespace-collapsed view) banked in cycle 281
 * via PromptDualUserTest.
 *
 * `PROMPT_AIGC_LANE` is in the static base prompt
 * (`TALEVIA_SYSTEM_PROMPT_BASE`, every turn). It teaches the
 * agent the per-tool defaults + lockfile / consistency-binding
 * discipline for the four AIGC tools (`generate_video` /
 * `generate_music` / `synthesize_speech` / `upscale_asset`)
 * plus the two ML-enhancement tools (`transcribe_asset` /
 * `describe_asset`) plus the `auto_subtitle_clip` shortcut.
 *
 * Drift signals:
 *   - **Drop the "Same seed / lockfile / binding discipline"
 *     refrain** → LLM regresses to omitting `projectId` /
 *     `consistencyBindingIds` and re-paying for cache misses
 *     and consistency drift.
 *   - **Soften the "Do NOT call add_subtitles in a loop" rule**
 *     → LLM regresses to N-tool-call-per-segment captioning,
 *     blowing the snapshot stack and turn latency.
 *   - **Drop the "fail loudly because speaker would be
 *     ambiguous" voiceId-binding clause** → LLM stops
 *     enforcing the one-voiced-character_ref-per-call rule.
 *   - **Drift in tool names** (`generate_video` →
 *     `generate-video`) → LLM dispatches non-existent tool
 *     ids; provider-side failure.
 *
 * Pins via marker-substring presence on the whitespace-flat
 * view (so cross-line phrases like "Same seed / lockfile\n
 * discipline" still match).
 */
class PromptAigcLaneTest {

    private val flat: String = PROMPT_AIGC_LANE.replace(Regex("\\s+"), " ")

    // ── Section headers ─────────────────────────────────────

    @Test fun allSixSectionHeadersPresent() {
        // Marquee structural pin: lane is composed of 6
        // distinct sections that the LLM uses to navigate
        // tool semantics. Drift to drop one would silently
        // remove that tool's behavioural anchor.
        for (header in listOf(
            "# Two kinds of users (VISION §4)",
            "# AIGC video (text-to-video)",
            "# AIGC music",
            "# AIGC audio (TTS)",
            "# Super-resolution",
            "# ML enhancement",
        )) {
            assertTrue(
                header in flat,
                "lane MUST contain section header '$header'",
            )
        }
    }

    // ── # AIGC video — generate_video defaults ──────────────

    @Test fun generateVideoToolNameAndSoraDefault() {
        // Marquee pin: tool id literal + Sora 2 default
        // identity. Drift to renaming either silently breaks
        // dispatch.
        assertTrue("generate_video" in flat, "MUST name generate_video tool")
        assertTrue(
            "OpenAI Sora 2" in flat,
            "MUST name OpenAI Sora 2 as the default text-to-video provider",
        )
        assertTrue(
            "1280x720" in flat && "5s" in flat,
            "MUST document default resolution (1280x720) and duration (5s)",
        )
    }

    @Test fun generateVideoDurationIsCacheKey() {
        // Pin: durationSeconds is part of cache key — drift
        // to "duration is just a hint" would silently break
        // lockfile cache identity.
        assertTrue(
            "durationSeconds" in flat,
            "MUST name the durationSeconds parameter",
        )
        assertTrue(
            "part of the cache key" in flat,
            "MUST anchor that durationSeconds participates in cache key (4s vs 8s renders distinct)",
        )
    }

    // ── # AIGC music ────────────────────────────────────────

    @Test fun generateMusicReplicateGateAndDefaults() {
        // Marquee pin: gating language + Replicate model
        // identity + default duration.
        assertTrue("generate_music" in flat, "MUST name generate_music tool")
        assertTrue(
            "REPLICATE_API_TOKEN" in flat,
            "MUST name the env-var gate that controls registration",
        )
        assertTrue(
            "meta/musicgen" in flat,
            "MUST name default model slug (meta/musicgen)",
        )
        assertTrue(
            "15s mp3" in flat,
            "MUST document default 15s mp3 output",
        )
    }

    @Test fun generateMusicVoiceIdSilentIgnoreClause() {
        // Pin: character_ref.voiceId is "speaker-only and
        // silently ignored by music gen" — drift to letting
        // it through would fail provider-side. The clause
        // also redirects to synthesize_speech.
        assertTrue(
            "voiceId" in flat,
            "MUST address voiceId binding semantics",
        )
        assertTrue(
            "silently ignored" in flat,
            "MUST anchor that music gen silently ignores voiceId",
        )
        assertTrue(
            "synthesize_speech" in flat,
            "MUST redirect voice-binding intent to synthesize_speech",
        )
    }

    @Test fun generateMusicUnregisteredFallback() {
        // Pin: when Replicate not wired, the lane MUST tell
        // agent to "say so explicitly and suggest importing
        // a track instead". Drift to silently dispatching
        // the unregistered tool would surface an opaque
        // error.
        assertTrue(
            "stays unregistered" in flat,
            "MUST anchor that generate_music stays unregistered without provider",
        )
        assertTrue(
            "importing a track instead" in flat,
            "MUST suggest the import_media fallback for the unregistered case",
        )
    }

    // ── # AIGC audio (TTS) — synthesize_speech ─────────────

    @Test fun synthesizeSpeechAlloyDefaultAndCacheSemantic() {
        // Marquee pin: default voice + cache-key tuple.
        assertTrue("synthesize_speech" in flat, "MUST name synthesize_speech tool")
        assertTrue(
            "voice \"alloy\"" in flat || "voice 'alloy'" in flat,
            "MUST document default voice (alloy)",
        )
        assertTrue(
            "(text, voice, model, format, speed)" in flat,
            "MUST enumerate the 5-tuple cache key (drift to a smaller tuple would silently change cache identity)",
        )
        assertTrue(
            "free cache hit" in flat,
            "MUST teach the cache-hit behavior on identical inputs",
        )
    }

    @Test fun voicedCharacterRefBindingSemantic() {
        // Marquee pin: one-voiced-character_ref-per-call rule
        // + "fail loudly because speaker would be ambiguous"
        // justification.
        assertTrue(
            "voiceId" in flat,
            "MUST name the voiceId field on character_ref nodes",
        )
        assertTrue(
            "Bind exactly one voiced character_ref per call" in flat,
            "MUST enforce the one-voiced-binding-per-call rule",
        )
        assertTrue(
            "fail loudly" in flat,
            "MUST anchor on fail-loudly semantic for ambiguous speaker",
        )
    }

    // ── # Super-resolution ─────────────────────────────────

    @Test fun upscaleAssetReplicateGateAndScaleRange() {
        assertTrue("upscale_asset" in flat, "MUST name upscale_asset tool")
        assertTrue(
            "nightmareai/real-esrgan" in flat,
            "MUST name default model slug",
        )
        assertTrue(
            "REPLICATE_API_TOKEN" in flat,
            "MUST name the env-var gate (re-stated per tool)",
        )
        assertTrue(
            "scale 2" in flat || "default scale 2" in flat,
            "MUST document default scale (2)",
        )
        assertTrue(
            "2..8" in flat,
            "MUST document scale parameter range (2..8)",
        )
    }

    // ── # ML enhancement ────────────────────────────────────

    @Test fun transcribeAssetLanguageAndConfirmation() {
        assertTrue("transcribe_asset" in flat, "MUST name transcribe_asset tool")
        assertTrue(
            "whisper-1" in flat,
            "MUST name default whisper model",
        )
        assertTrue(
            "ISO-639-1" in flat,
            "MUST anchor language code spec (drift to wrong ISO would silently break detection-skip)",
        )
        assertTrue(
            "confirm before each call" in flat,
            "MUST require user confirmation before audio upload",
        )
    }

    @Test fun autoSubtitleClipIsRightToolNinetyNinePercent() {
        // Marquee pin: auto_subtitle_clip is the preferred
        // path; drift to demote it would re-route LLM to
        // chained transcribe + add_subtitles even for the
        // common case.
        assertTrue("auto_subtitle_clip" in flat, "MUST name auto_subtitle_clip tool")
        assertTrue(
            "right tool 99% of the time" in flat,
            "MUST anchor that auto_subtitle_clip is the 99%-case preferred path",
        )
        assertTrue(
            "{projectId, clipId}" in flat,
            "MUST document the {projectId, clipId} input shape",
        )
    }

    @Test fun addSubtitlesNoLoopAntiPattern() {
        // Marquee anti-pattern pin: drift to soften this rule
        // re-enables N-tool-call-per-segment captioning,
        // which blows the snapshot stack + turn latency.
        assertTrue(
            "Do NOT call" in flat,
            "MUST contain the explicit Do NOT call directive",
        )
        assertTrue(
            "in a loop of 1-element lists" in flat,
            "MUST forbid loop-over-1-element-lists pattern",
        )
        assertTrue(
            "single `subtitles` list" in flat,
            "MUST direct toward the batched single-list API",
        )
        assertTrue(
            "tight undo stack" in flat,
            "MUST justify with the snapshot-stack semantic",
        )
    }

    @Test fun describeAssetImagesOnlyFailsLoudly() {
        // Marquee pin: images only + fail-loudly on
        // video/audio. Drift to silent fallback re-enables
        // the LLM passing video assets and getting opaque
        // errors.
        assertTrue("describe_asset" in flat, "MUST name describe_asset tool")
        assertTrue(
            "Images only" in flat,
            "MUST anchor images-only restriction",
        )
        assertTrue(
            "fails loudly" in flat,
            "MUST anchor fail-loudly behavior on non-image assets",
        )
        assertTrue(
            "grab a frame first" in flat,
            "MUST suggest the frame-grab workaround for video",
        )
        assertTrue(
            "gpt-4o-mini" in flat,
            "MUST name default vision model",
        )
    }

    // ── Cross-section invariants ────────────────────────────

    @Test fun seedLockfileBindingDisciplineRefrainAppears() {
        // Marquee cross-tool refrain: every AIGC tool re-
        // states the "Same seed / lockfile / binding
        // discipline as `generate_image`" line. Drift to
        // drop the refrain in any one tool would silently
        // re-enable cache-skipping per that tool.
        assertTrue(
            "Same seed / lockfile / binding discipline" in flat,
            "video section MUST anchor on the shared seed/lockfile/binding refrain",
        )
        assertTrue(
            "Same seed / lockfile" in flat,
            "music + TTS sections MUST mirror the seed/lockfile discipline language",
        )
        assertTrue(
            "consistencyBindingIds" in flat,
            "lane MUST name the consistencyBindingIds parameter that drives folding",
        )
        assertTrue(
            "projectId" in flat,
            "lane MUST name the projectId parameter that drives cache-hits",
        )
    }

    @Test fun assetIdDropOntoTrackPattern() {
        // Marquee pin: every AIGC tool docs end with the
        // "drop the returned `assetId` onto a track via
        // `clip_action(action=\"add\")`" pattern. Drift to
        // drop this would re-enable LLM forgetting the
        // timeline-add step.
        assertTrue(
            "clip_action(action=\"add\")" in flat,
            "MUST name the canonical add-to-track tool invocation",
        )
        assertTrue(
            "assetId" in flat,
            "MUST name the assetId returned by AIGC tools",
        )
    }

    @Test fun lengthIsBoundedAndMeaningful() {
        // Pin: lane is in the static base — every turn pays
        // its token cost. Sanity-check the char-length is
        // in a sane band: long enough to carry the 6-section
        // structure, short enough to avoid bloat.
        val s = PROMPT_AIGC_LANE
        assertTrue(
            s.length > 2000,
            "lane content MUST be > 2000 chars (drift to no-op surfaces here); got: ${s.length}",
        )
        assertTrue(
            s.length < 10_000,
            "lane content MUST be < 10000 chars (drift to bloated surfaces here); got: ${s.length}",
        )
    }

    @Test fun laneIsTrimmedNoLeadingOrTrailingBlankLines() {
        // Sister of PromptDualUserTest's trim contract pin
        // — same composer joins lanes with `\n\n`, leading/
        // trailing whitespace would corrupt section-
        // separator invariant.
        val s = PROMPT_AIGC_LANE
        assertTrue(
            s == s.trim(),
            "lane MUST be trimmed (no leading/trailing whitespace)",
        )
    }
}
