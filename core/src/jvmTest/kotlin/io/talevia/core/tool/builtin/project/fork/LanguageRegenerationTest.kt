package io.talevia.core.tool.builtin.project.fork

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [regenerateTtsInLanguage] —
 * `core/tool/builtin/project/fork/LanguageRegeneration.kt`. The
 * post-fork TTS regeneration helper that dispatches the registered
 * `synthesize_speech` tool against every Text clip on a fork's
 * timeline. Cycle 212 audit: 74 LOC, 0 direct test refs.
 *
 * Same audit-pattern fallback as cycles 207-211. Direct symbol-level
 * testing here is especially valuable: a fake `synthesize_speech`
 * Tool can record the dispatch payload, letting us pin the exact
 * JSON shape (text / projectId / language / consistencyBindingIds)
 * the agent + the lockfile cache key both depend on. Drift in the
 * payload shape would silently miss cache hits across forks.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Language must be non-blank ISO-639-1.** Blank (empty / whitespace-
 *     only) string fails fast with a remediation hint listing
 *     example codes ('en' / 'es'). Drift would dispatch with
 *     `language=""` and crash deeper in the TTS engine with no
 *     hint.
 *
 *  2. **Registry / tool-not-found fail loud.** When the registry is
 *     `null` or `synthesize_speech` is unregistered, the helper
 *     throws with a friendly remediation hint (cite the registry
 *     mount point + `TtsEngine` / `AigcSpeechGenerator` wiring) so
 *     the agent can either drop variantSpec.language or fix the
 *     container. Drift to "silent skip" would leave the fork with
 *     source-language voiceovers and the user oblivious.
 *
 *  3. **Iterates Text clips only.** Video / Audio clips on the
 *     timeline are skipped without dispatch. Drift to "iterate
 *     all clips" would call synthesize_speech on Video and crash
 *     on the missing `text` field.
 *
 *  4. **Blank `clip.text` skipped.** Empty Text clips no-op (no
 *     dispatch, no result row). Drift to "synthesize empty text"
 *     would either crash or burn provider tokens for nothing.
 *
 *  5. **Dispatch payload shape (marquee).** The JSON sent to
 *     `synthesize_speech` includes `text` + `projectId` + `language`
 *     + sorted `consistencyBindingIds` (when non-empty). The
 *     bindings field is OMITTED when empty (NOT serialised as an
 *     empty array). Drift would either change the lockfile cache
 *     key (different inputHash) or crash on schema mismatch.
 *
 *  6. **Output unpacking: assetId required, cacheHit boolean.**
 *     Per-clip result row carries `assetId` (required — error if
 *     missing) and `cacheHit` (defaults to false unless the output's
 *     primitive content is the literal string "true"). Drift in
 *     `cacheHit` parsing would mis-report billing-relevant state.
 *
 * Plus shape pins: result list ordering matches Text-clip iteration
 * order across tracks; sorted bindings (NOT input order); empty
 * timeline → empty results.
 */
class LanguageRegenerationTest {

    /**
     * Fake `synthesize_speech` tool — records every dispatch's input
     * payload and returns a constructor-supplied output. Lets the
     * test inspect what the helper sent without standing up a real
     * provider.
     */
    private class FakeSynthesizeSpeech(
        private val outputProvider: (FakeInput) -> FakeOutput,
    ) : Tool<FakeInput, FakeOutput> {
        val dispatches: MutableList<FakeInput> = mutableListOf()

        override val id: String = "synthesize_speech"
        override val helpText: String = "test fake"
        override val inputSchema: JsonObject = JsonObject(emptyMap())
        override val inputSerializer: KSerializer<FakeInput> = serializer()
        override val outputSerializer: KSerializer<FakeOutput> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("aigc.speech")

        override suspend fun execute(input: FakeInput, ctx: ToolContext): ToolResult<FakeOutput> {
            dispatches += input
            val out = outputProvider(input)
            return ToolResult(title = "tts", outputForLlm = "tts done", data = out)
        }
    }

    @Serializable
    data class FakeInput(
        val text: String,
        val projectId: String,
        val language: String,
        val consistencyBindingIds: List<String> = emptyList(),
    )

    @Serializable
    data class FakeOutput(
        val assetId: String,
        val cacheHit: Boolean = false,
    )

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun textClip(
        id: String,
        text: String = "hello",
        bindings: List<String> = emptyList(),
        timelineStart: Long = 0,
        timelineDuration: Long = 5,
    ): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(timelineStart.seconds, timelineDuration.seconds),
        text = text,
        style = TextStyle(),
        sourceBinding = bindings.map { SourceNodeId(it) }.toSet(),
    )

    private fun videoClip(id: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId("v-$id"),
    )

    private fun fork(tracks: List<Track> = emptyList()): Project = Project(
        id = ProjectId("fork-id"),
        timeline = Timeline(tracks = tracks),
    )

    private fun registryWith(tool: Tool<*, *>): ToolRegistry =
        ToolRegistry().apply { register(tool) }

    // ── 1. Language validation ──────────────────────────────

    @Test fun blankLanguageRejected() = runTest {
        val tts = FakeSynthesizeSpeech { FakeOutput(assetId = "a", cacheHit = false) }
        val registry = registryWith(tts)
        for (blank in listOf("", " ", "\t", "  \n  ")) {
            val ex = assertFailsWith<IllegalArgumentException> {
                regenerateTtsInLanguage(
                    registry = registry,
                    forkId = ProjectId("fork-id"),
                    fork = fork(),
                    language = blank,
                    ctx = ctx(),
                )
            }
            val msg = ex.message ?: ""
            assertTrue(
                "must be a non-blank ISO-639-1 code" in msg,
                "expected ISO-639-1 hint for '$blank'; got: $msg",
            )
            assertTrue("'en'" in msg && "'es'" in msg, "expected example codes; got: $msg")
        }
        assertEquals(0, tts.dispatches.size, "no dispatch should occur on blank language")
    }

    // ── 2. Registry / tool-not-found fail loud ──────────────

    @Test fun nullRegistryFailsWithFriendlyHint() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            regenerateTtsInLanguage(
                registry = null,
                forkId = ProjectId("fork-id"),
                fork = fork(),
                language = "es",
                ctx = ctx(),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "no ToolRegistry wired" in msg,
            "expected registry-not-wired phrase; got: $msg",
        )
        assertTrue(
            "TtsEngine/AigcSpeechGenerator" in msg,
            "expected TTS wiring hint; got: $msg",
        )
        assertTrue(
            "drop variantSpec.language" in msg,
            "expected drop-language remediation; got: $msg",
        )
    }

    @Test fun missingSynthesizeSpeechToolFailsWithFriendlyHint() = runTest {
        // Registry has tools but NOT synthesize_speech. Drift to
        // "silent no-op" would leave the fork with original-language
        // audio undetected.
        val empty = ToolRegistry()
        val ex = assertFailsWith<IllegalStateException> {
            regenerateTtsInLanguage(
                registry = empty,
                forkId = ProjectId("fork-id"),
                fork = fork(),
                language = "es",
                ctx = ctx(),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "requires the `synthesize_speech` tool to be registered" in msg,
            "expected tool-id citation; got: $msg",
        )
        assertTrue(
            "wire a TtsEngine or drop variantSpec.language" in msg,
            "expected remediation hint; got: $msg",
        )
    }

    // ── 3. Iterates Text clips only ─────────────────────────

    @Test fun videoAndAudioClipsAreSkipped() = runTest {
        // Pin: per impl `if (clip !is Clip.Text) continue`. Drift
        // to "iterate all clips" would crash on Video.text access.
        val tts = FakeSynthesizeSpeech {
            FakeOutput(assetId = "a-${it.text}", cacheHit = false)
        }
        val registry = registryWith(tts)
        val tracks = listOf(
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("vc"))),
            Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("tc1", "hello"))),
            Track.Audio(
                id = TrackId("a"),
                clips = listOf(
                    Clip.Audio(
                        id = ClipId("ac"),
                        timeRange = TimeRange(0.seconds, 5.seconds),
                        sourceRange = TimeRange(0.seconds, 5.seconds),
                        assetId = AssetId("a-ac"),
                    ),
                ),
            ),
        )
        val results = regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        // Only the one Text clip dispatched.
        assertEquals(1, tts.dispatches.size)
        assertEquals("hello", tts.dispatches[0].text)
        assertEquals(1, results.size)
        assertEquals("tc1", results[0].clipId)
    }

    // ── 4. Blank text clip skipped ──────────────────────────

    @Test fun blankTextClipSkipped() = runTest {
        // Pin: per impl `if (clip.text.isBlank()) continue`. Drift
        // to "synthesize empty text" would either crash or burn
        // tokens.
        val tts = FakeSynthesizeSpeech { FakeOutput(assetId = "a-${it.text}") }
        val registry = registryWith(tts)
        val tracks = listOf(
            Track.Subtitle(
                id = TrackId("s"),
                clips = listOf(
                    textClip("blank-empty", text = ""),
                    textClip("blank-ws", text = "   "),
                    textClip("real", text = "hola"),
                ),
            ),
        )
        val results = regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        assertEquals(1, tts.dispatches.size, "only the non-blank clip dispatched")
        assertEquals("hola", tts.dispatches[0].text)
        assertEquals(listOf("real"), results.map { it.clipId })
    }

    // ── 5. Dispatch payload shape (marquee) ─────────────────

    @Test fun dispatchPayloadCarriesTextProjectIdAndLanguage() = runTest {
        val tts = FakeSynthesizeSpeech { FakeOutput(assetId = "a") }
        val registry = registryWith(tts)
        val tracks = listOf(
            Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("c1", "hola mundo"))),
        )
        regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id-99"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        val payload = tts.dispatches[0]
        assertEquals("hola mundo", payload.text)
        assertEquals("fork-id-99", payload.projectId, "projectId is the FORK id, not source")
        assertEquals("es", payload.language)
    }

    @Test fun emptyBindingsFieldOmittedFromPayload() = runTest {
        // Marquee bindings-shape pin: empty `clip.sourceBinding` →
        // payload omits `consistencyBindingIds` entirely (NOT empty
        // array). The lockfile cache key includes/excludes this
        // field based on its presence; drift would silently miss
        // cache hits on identical inputs.
        //
        // We can verify this by inspecting the raw JSON the helper
        // built — bypass the FakeInput decoder and look at the raw
        // JsonElement from the dispatch site.
        val capturedRaw = mutableListOf<JsonElement>()
        val tool = object : Tool<JsonObject, FakeOutput> {
            override val id = "synthesize_speech"
            override val helpText = "raw fake"
            override val inputSchema: JsonObject = JsonObject(emptyMap())
            override val inputSerializer: KSerializer<JsonObject> = serializer()
            override val outputSerializer: KSerializer<FakeOutput> = serializer()
            override val permission: PermissionSpec = PermissionSpec.fixed("aigc.speech")
            override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult<FakeOutput> {
                capturedRaw += input
                return ToolResult(
                    title = "tts",
                    outputForLlm = "ok",
                    data = FakeOutput(assetId = "a"),
                )
            }
        }
        val registry = ToolRegistry().apply { register(tool) }
        val tracks = listOf(
            Track.Subtitle(
                id = TrackId("s"),
                clips = listOf(textClip("c1", "hello", bindings = emptyList())),
            ),
        )
        regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        val payload = capturedRaw[0].jsonObject
        assertTrue("text" in payload.keys, "text present")
        assertTrue("projectId" in payload.keys, "projectId present")
        assertTrue("language" in payload.keys, "language present")
        assertTrue(
            "consistencyBindingIds" !in payload.keys,
            "consistencyBindingIds OMITTED when bindings empty (NOT empty array); got keys: ${payload.keys}",
        )
    }

    @Test fun nonEmptyBindingsAreSorted() = runTest {
        // Pin: per impl `clip.sourceBinding.map { it.value }.sorted()`.
        // Bindings are sorted alphabetically before serialisation —
        // input set order doesn't affect the payload (and therefore
        // doesn't affect the lockfile cache key).
        val capturedRaw = mutableListOf<JsonElement>()
        val tool = object : Tool<JsonObject, FakeOutput> {
            override val id = "synthesize_speech"
            override val helpText = "raw fake"
            override val inputSchema: JsonObject = JsonObject(emptyMap())
            override val inputSerializer: KSerializer<JsonObject> = serializer()
            override val outputSerializer: KSerializer<FakeOutput> = serializer()
            override val permission: PermissionSpec = PermissionSpec.fixed("aigc.speech")
            override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult<FakeOutput> {
                capturedRaw += input
                return ToolResult(
                    title = "tts",
                    outputForLlm = "ok",
                    data = FakeOutput(assetId = "a"),
                )
            }
        }
        val registry = ToolRegistry().apply { register(tool) }
        val tracks = listOf(
            Track.Subtitle(
                id = TrackId("s"),
                clips = listOf(
                    textClip("c1", "hi", bindings = listOf("z-style", "a-character", "m-mood")),
                ),
            ),
        )
        regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        val payload = capturedRaw[0].jsonObject
        val bindings = payload["consistencyBindingIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("a-character", "m-mood", "z-style"),
            bindings,
            "bindings sorted alphabetically — input set order does NOT survive",
        )
    }

    // ── 6. Output unpacking ─────────────────────────────────

    @Test fun missingAssetIdFailsLoudWithClipIdInError() = runTest {
        // Pin: if synthesize_speech returns output without an
        // `assetId`, the helper errors citing which clip ran into
        // the missing field. Drift to silent null would land a
        // result with assetId="" and break downstream.
        val tool = object : Tool<JsonObject, JsonObject> {
            override val id = "synthesize_speech"
            override val helpText = "no-asset fake"
            override val inputSchema: JsonObject = JsonObject(emptyMap())
            override val inputSerializer: KSerializer<JsonObject> = serializer()
            override val outputSerializer: KSerializer<JsonObject> = serializer()
            override val permission: PermissionSpec = PermissionSpec.fixed("aigc.speech")
            override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult<JsonObject> =
                ToolResult(
                    title = "tts",
                    outputForLlm = "missing assetId",
                    data = buildJsonObject { /* no assetId */ },
                )
        }
        val registry = ToolRegistry().apply { register(tool) }
        val tracks = listOf(
            Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("orphan", "hi"))),
        )
        val ex = assertFailsWith<IllegalStateException> {
            regenerateTtsInLanguage(
                registry = registry,
                forkId = ProjectId("fork-id"),
                fork = fork(tracks),
                language = "es",
                ctx = ctx(),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("returned no assetId" in msg, "expected missing-assetId message; got: $msg")
        assertTrue("clip orphan" in msg, "expected clip id cited; got: $msg")
    }

    @Test fun cacheHitParsedAsLiteralStringTrue() = runTest {
        // Pin: per impl `(outputJson["cacheHit"] as? JsonPrimitive)?.content == "true"`.
        // Only the literal string "true" maps to cacheHit=true; everything
        // else (false, 1, "True", missing) maps to false. This is the
        // historical contract that pre-dates type-strict JSON parsing.
        val cases = mapOf(
            "true" to true,
            "false" to false,
            "True" to false, // case-sensitive
            "1" to false,
            "" to false,
        )
        for ((field, expected) in cases) {
            val tool = object : Tool<JsonObject, JsonObject> {
                override val id = "synthesize_speech"
                override val helpText = "cache fake"
                override val inputSchema: JsonObject = JsonObject(emptyMap())
                override val inputSerializer: KSerializer<JsonObject> = serializer()
                override val outputSerializer: KSerializer<JsonObject> = serializer()
                override val permission: PermissionSpec = PermissionSpec.fixed("aigc.speech")
                override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult<JsonObject> =
                    ToolResult(
                        title = "tts",
                        outputForLlm = "tts",
                        data = buildJsonObject {
                            put("assetId", JsonPrimitive("a"))
                            put("cacheHit", JsonPrimitive(field))
                        },
                    )
            }
            val registry = ToolRegistry().apply { register(tool) }
            val tracks = listOf(
                Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("c1", "hi"))),
            )
            val results = regenerateTtsInLanguage(
                registry = registry,
                forkId = ProjectId("fork-id"),
                fork = fork(tracks),
                language = "es",
                ctx = ctx(),
            )
            assertEquals(
                expected,
                results[0].cacheHit,
                "cacheHit field='$field' → expected $expected; got ${results[0].cacheHit}",
            )
        }
    }

    @Test fun missingCacheHitFieldDefaultsFalse() = runTest {
        // Pin: per impl `?.content == "true"` — when the field is
        // absent, the safe-cast yields null, `null == "true"` is
        // false. Drift to "default true" would over-report cache
        // hits.
        val tool = object : Tool<JsonObject, JsonObject> {
            override val id = "synthesize_speech"
            override val helpText = "no-cache-field fake"
            override val inputSchema: JsonObject = JsonObject(emptyMap())
            override val inputSerializer: KSerializer<JsonObject> = serializer()
            override val outputSerializer: KSerializer<JsonObject> = serializer()
            override val permission: PermissionSpec = PermissionSpec.fixed("aigc.speech")
            override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult<JsonObject> =
                ToolResult(
                    title = "tts",
                    outputForLlm = "tts",
                    data = buildJsonObject { put("assetId", JsonPrimitive("a")) },
                )
        }
        val registry = ToolRegistry().apply { register(tool) }
        val tracks = listOf(
            Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("c1", "hi"))),
        )
        val results = regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        assertEquals(false, results[0].cacheHit, "missing cacheHit defaults to false")
    }

    // ── Result list ordering / multi-clip ──────────────────

    @Test fun resultListPreservesClipIterationOrderAcrossTracks() = runTest {
        // Pin: results follow timeline.tracks → track.clips iteration
        // order. Track A's text clips come before Track B's, even
        // when they appear later on the actual timeline timestamp.
        val tts = FakeSynthesizeSpeech { FakeOutput(assetId = "a-${it.text}") }
        val registry = registryWith(tts)
        val tracks = listOf(
            Track.Subtitle(
                id = TrackId("track-a"),
                clips = listOf(textClip("a1", "first"), textClip("a2", "second")),
            ),
            Track.Subtitle(
                id = TrackId("track-b"),
                clips = listOf(textClip("b1", "third"), textClip("b2", "fourth")),
            ),
        )
        val results = regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        assertEquals(
            listOf("a1", "a2", "b1", "b2"),
            results.map { it.clipId },
            "results follow track-then-clip iteration order",
        )
        // Each result carries assetId from the fake.
        assertEquals(
            listOf("a-first", "a-second", "a-third", "a-fourth"),
            results.map { it.assetId },
        )
    }

    @Test fun emptyTimelineReturnsEmptyResultsAndNoDispatch() = runTest {
        val tts = FakeSynthesizeSpeech { FakeOutput(assetId = "a") }
        val registry = registryWith(tts)
        val results = regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(emptyList()),
            language = "es",
            ctx = ctx(),
        )
        assertEquals(0, results.size)
        assertEquals(0, tts.dispatches.size)
    }

    @Test fun cacheHitTrueReportedThrough() = runTest {
        // Sanity: cacheHit=true round-trips end-to-end.
        val tts = FakeSynthesizeSpeech { FakeOutput(assetId = "asset-1", cacheHit = true) }
        val registry = registryWith(tts)
        val tracks = listOf(
            Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("c1", "hi"))),
        )
        val results = regenerateTtsInLanguage(
            registry = registry,
            forkId = ProjectId("fork-id"),
            fork = fork(tracks),
            language = "es",
            ctx = ctx(),
        )
        assertEquals("asset-1", results[0].assetId)
        assertEquals(true, results[0].cacheHit)
        // Sanity: only one dispatch happened.
        assertEquals(1, tts.dispatches.size)
        assertNull(
            tts.dispatches[0].consistencyBindingIds.firstOrNull(),
            "no bindings on this clip → empty list (deserialised to default empty)",
        )
    }
}
