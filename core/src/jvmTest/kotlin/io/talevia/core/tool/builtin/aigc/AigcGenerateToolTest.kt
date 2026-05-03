package io.talevia.core.tool.builtin.aigc

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * `debt-aigc-tool-consolidation` phase 1 — `AigcGenerateTool` dispatches
 * to the existing 4 AIGC tools by `kind` discriminator. This suite pins
 * the dispatch contract: each kind round-trips through the inner tool
 * and projects to the unified [AigcGenerateTool.Output] shape; missing
 * engines fail loud at dispatch time (not at registration).
 *
 * The underlying per-kind correctness (lockfile cache, consistency
 * folding, asset persist) is exhaustively tested by
 * `AigcImageGeneratorTest` / `AigcVideoGeneratorTest` /
 * `AigcMusicGeneratorTest` / `AigcSpeechGeneratorTest` — this suite
 * only verifies the dispatcher faithfully relays input + projects
 * output.
 */
class AigcGenerateToolTest {

    private val tinyPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val tinyMp4 = byteArrayOf(0, 0, 0, 0x1c, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    private val tinyMp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    private val tinyMusic = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun imageKindDispatchesAndProjectsToUnifiedOutput() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/img".toPath(), title = "img").id
        val engine = OneShotImageGenEngine(tinyPng, providerId = "fake-image")
        val image = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(image = image)

        val out = tool.execute(
            AigcGenerateTool.Input.Image(
                prompt = "a cat",
                width = 512,
                height = 512,
                seed = 42L,
                projectId = pid.value,
            ),
            ctx(),
        ).data

        assertEquals("image", out.kind)
        assertEquals("fake-image", out.providerId)
        assertEquals(42L, out.seed)
        assertEquals(512, out.width)
        assertEquals(512, out.height)
        assertEquals(1, engine.calls, "inner AigcImageGenerator was dispatched exactly once")
        // Speech-only fields are null on image output.
        assertEquals(null, out.voice)
        assertEquals(null, out.format)
        // Video-only fields too.
        assertEquals(null, out.durationSeconds)
    }

    @Test fun videoKindDispatchesAndPopulatesDurationField() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/vid".toPath(), title = "vid").id
        val engine = OneShotVideoGenEngine(tinyMp4, providerId = "fake-video")
        val video = AigcVideoGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(video = video)

        val out = tool.execute(
            AigcGenerateTool.Input.Video(
                prompt = "a cat walking",
                durationSeconds = 3.0,
                width = 640,
                height = 360,
                seed = 7L,
                projectId = pid.value,
            ),
            ctx(),
        ).data

        assertEquals("video", out.kind)
        assertEquals(3.0, out.durationSeconds)
        assertEquals(640, out.width)
        assertEquals(360, out.height)
        assertEquals(7L, out.seed)
        assertEquals(1, engine.calls)
    }

    @Test fun musicKindDispatchesAndPopulatesDurationField() = runTest {
        // Cycle 72 follow-up: the dispatcher's 4-kind matrix had image /
        // video / speech routing covered; music routing was missing. Pin
        // the contract: `Input.Music` reaches `AigcMusicGenerator`,
        // result.kind == "music", and the music-specific `durationSeconds`
        // field surfaces correctly through the unified Output.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/mus".toPath(), title = "mus").id
        val engine = OneShotMusicGenEngine(tinyMusic, providerId = "fake-music")
        val music = AigcMusicGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(music = music)

        val out = tool.execute(
            AigcGenerateTool.Input.Music(
                prompt = "lo-fi beats",
                durationSeconds = 12.0,
                model = "musicgen",
                seed = 11L,
                projectId = pid.value,
            ),
            ctx(),
        ).data

        assertEquals("music", out.kind)
        assertEquals("fake-music", out.providerId)
        assertEquals(12.0, out.durationSeconds)
        // image/video-specific dimensions should be null on a music result.
        assertEquals(null, out.width)
        assertEquals(null, out.height)
        // speech-specific fields (voice/format) should be null on music.
        assertEquals(null, out.voice)
        assertEquals(null, out.format)
        assertEquals(11L, out.seed)
        assertEquals(1, engine.calls)
    }

    @Test fun speechKindMapsTextThroughPromptField() = runTest {
        // For speech: the dispatcher's `prompt` carries the spoken text;
        // it gets relayed to AigcSpeechGenerator.Input.text. Verify the
        // round-trip preserves voice / format and surfaces the speech-
        // specific output fields.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/spk".toPath(), title = "spk").id
        val engine = OneShotTtsEngine(tinyMp3, providerId = "fake-tts")
        val speech = AigcSpeechGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(speech = speech)

        val out = tool.execute(
            AigcGenerateTool.Input.Speech(
                prompt = "hello world",
                voice = "echo",
                format = "mp3",
                projectId = pid.value,
            ),
            ctx(),
        ).data

        assertEquals("speech", out.kind)
        assertEquals("fake-tts", out.providerId)
        assertEquals("echo", out.voice)
        assertEquals("mp3", out.format)
        assertEquals("hello world", out.effectivePrompt)
        assertEquals(1, engine.calls)
    }

    @Test fun missingEngineFailsLoudWithKindSpecificMessage() = runTest {
        // No image engine wired → `kind=image` dispatch throws at execute
        // time with a kind-tagged message that says which engine is
        // missing. This is the "engine not configured" diagnostic shape
        // the registry's null-skip would otherwise produce silently.
        val tool = AigcGenerateTool() // all engines null

        val ex = assertFails {
            tool.execute(
                AigcGenerateTool.Input.Image(prompt = "anything"),
                ctx(),
            )
        }
        assertTrue(
            ex.message?.contains("kind=image") == true && ex.message!!.contains("engine not configured"),
            "image-kind dispatch should name the missing engine: got '${ex.message}'",
        )
    }

    @Test fun missingEngineForOneKindDoesNotBlockOthers() = runTest {
        // Mixed wiring: only image engine present. `image` dispatch
        // succeeds; `video` / `music` / `speech` dispatch fails loud.
        // Confirms partial-engine tolerance — same shape as the
        // existing per-kind tools' nullable-engine pattern in
        // `registerAigcTools`.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/mix".toPath(), title = "mix").id
        val engine = OneShotImageGenEngine(tinyPng, providerId = "fake-image")
        val image = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(image = image) // video / music / speech absent

        // Image kind succeeds.
        val imgOut = tool.execute(
            AigcGenerateTool.Input.Image(prompt = "a", projectId = pid.value),
            ctx(),
        ).data
        assertEquals("image", imgOut.kind)

        // Video kind fails loud with a video-specific message.
        val vidEx = assertFails {
            tool.execute(
                AigcGenerateTool.Input.Video(prompt = "b", projectId = pid.value),
                ctx(),
            )
        }
        assertTrue(vidEx.message!!.contains("kind=video"), "video failure tagged: ${vidEx.message}")
    }

    // ---------- aigc-multi-variant-phase2-dispatch (cycle 29) ---------

    @Test fun n1DispatchPopulatesSingleElementVariantsList() = runTest {
        // Regression for the default `variantCount=1` shape: even single-
        // variant calls produce a non-empty `variants` list (size 1) so
        // multi-variant-aware consumers can iterate uniformly. Top-level
        // assetId mirrors variants[0].assetId.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/n1".toPath(), title = "n1").id
        val engine = OneShotImageGenEngine(tinyPng, providerId = "fake-image")
        val image = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(image = image)

        val out = tool.execute(
            AigcGenerateTool.Input.Image(prompt = "x", projectId = pid.value),
            ctx(),
        ).data

        assertEquals(1, out.variantCount)
        assertEquals(1, out.variants.size, "even n=1 surfaces a single-element variants list")
        assertEquals(0, out.variants[0].variantIndex, "single variant has index 0")
        assertEquals(out.assetId, out.variants[0].assetId, "top-level assetId mirrors variant[0]")
    }

    @Test fun imageVariantCount4ProducesFourDistinctLockfileEntries() = runTest {
        // The core multi-variant invariant: variantCount=4 on image-gen
        // produces 4 inner-tool calls, 4 distinct lockfile entries with
        // variantIndex 0..3, and 4 distinct inputHashes (phase 1's
        // canonical-string variant segment). Each iteration's assetId
        // is unique because Uuid.random() mints fresh ids per call.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/n4img".toPath(), title = "n4img").id
        val engine = CountingImageGenEngine(
            providerId = "fake-counting",
            bytesForCall = { call -> byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, call.toByte()) },
        )
        val image = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(image = image)

        val out = tool.execute(
            AigcGenerateTool.Input.Image(
                prompt = "a hero shot",
                seed = 42L,
                projectId = pid.value,
                variantCount = 4,
            ),
            ctx(),
        ).data

        assertEquals(4, out.variantCount)
        assertEquals(4, out.variants.size)
        assertEquals(listOf(0, 1, 2, 3), out.variants.map { it.variantIndex })
        // 4 distinct asset ids — Uuid.random() per inner-tool call.
        assertEquals(4, out.variants.map { it.assetId }.toSet().size, "all 4 variants have distinct assetIds")
        assertEquals(4, engine.calls, "engine called once per variant (sequential N×1 — no native `n` batching)")

        // Lockfile must hold all 4 entries with variantIndex 0..3 and
        // 4 distinct inputHashes (variant segment makes them unique).
        val project = store.get(pid)!!
        val entries = project.lockfile.entries.filter { it.toolId == "generate_image" }
        assertEquals(4, entries.size, "4 lockfile entries persisted")
        assertEquals(setOf(0, 1, 2, 3), entries.map { it.variantIndex }.toSet())
        assertEquals(4, entries.map { it.inputHash }.toSet().size, "4 distinct inputHashes")
    }

    @Test fun videoVariantCount3SequentialCallsAndDistinctEntries() = runTest {
        // Cross-kind sanity: video also fans out through the same
        // sequential loop. The bullet text noted "non-`n`-supporting
        // providers (Sora 2 / Veo / TTS) 顺序 N 次调用" — this test pins
        // that the loop itself works for every kind (the per-provider
        // `n` optimisation is a follow-up; the dispatch shape is
        // identical either way).
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/n3vid".toPath(), title = "n3vid").id
        val engine = OneShotVideoGenEngine(tinyMp4, providerId = "fake-video")
        val video = AigcVideoGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(video = video)

        val out = tool.execute(
            AigcGenerateTool.Input.Video(
                prompt = "a chase scene",
                durationSeconds = 4.0,
                seed = 11L,
                projectId = pid.value,
                variantCount = 3,
            ),
            ctx(),
        ).data

        assertEquals(3, out.variantCount)
        assertEquals(3, out.variants.size)
        assertEquals(3, engine.calls, "video provider called 3 times (no native `n` for sora/veo)")
        val project = store.get(pid)!!
        val entries = project.lockfile.entries.filter { it.toolId == "generate_video" }
        assertEquals(3, entries.size)
        assertEquals(setOf(0, 1, 2), entries.map { it.variantIndex }.toSet())
    }

    @Test fun variantCountZeroRejectsLoudBeforeAnyDispatch() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/zero".toPath(), title = "zero").id
        val engine = OneShotImageGenEngine(tinyPng, providerId = "fake-image")
        val image = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(image = image)

        val ex = assertFails {
            tool.execute(
                AigcGenerateTool.Input.Image(prompt = "x", projectId = pid.value, variantCount = 0),
                ctx(),
            )
        }
        assertTrue("variantCount must be ≥ 1" in ex.message.orEmpty(), "got: ${ex.message}")
        assertEquals(0, engine.calls, "loud reject must happen before any provider call")
    }

    // ---------- aigc-multi-variant-phase3-openai-native-n (cycle 33) ----

    @Test fun nativeBatchEngineFiresOneProviderCallForVariantCount4() = runTest {
        // Cycle 33: when the underlying ImageGenEngine reports
        // `supportsNativeBatch = true` (OpenAI image-gen in production),
        // the dispatcher routes through `AigcImageGenerator.executeBatch`
        // which issues a **single** provider call with `n=variantCount`
        // instead of N separate `n=1` calls. The cycle 29 sequential
        // path is preserved as the fallback (asserted by
        // `imageVariantCount4ProducesFourDistinctLockfileEntries`,
        // above).
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/native-batch".toPath(), title = "native-batch").id
        val engine = NativeBatchImageGenEngine(
            providerId = "fake-native-batch",
            bytesForVariant = { call, v -> byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, call.toByte(), v.toByte()) },
        )
        val image = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(image = image)

        val out = tool.execute(
            AigcGenerateTool.Input.Image(
                prompt = "hero shot variants",
                seed = 42L,
                projectId = pid.value,
                variantCount = 4,
            ),
            ctx(),
        ).data

        // Single provider round-trip — the whole point of phase 3.
        assertEquals(1, engine.calls, "native-batch engine must be called exactly once for variantCount=4")
        // The single call must have requested n=4.
        assertEquals(4, engine.lastRequest?.n, "the single call must propagate n=variantCount through to the engine")

        // Same lockfile shape as the sequential path: 4 entries with
        // variantIndex 0..3, 4 distinct inputHashes (variant segment),
        // 4 distinct asset ids.
        assertEquals(4, out.variantCount)
        assertEquals(4, out.variants.size)
        assertEquals(listOf(0, 1, 2, 3), out.variants.map { it.variantIndex })
        assertEquals(4, out.variants.map { it.assetId }.toSet().size, "4 distinct assetIds")

        val project = store.get(pid)!!
        val entries = project.lockfile.entries.filter { it.toolId == "generate_image" }
        assertEquals(4, entries.size, "4 lockfile entries persisted")
        assertEquals(setOf(0, 1, 2, 3), entries.map { it.variantIndex }.toSet())
        assertEquals(4, entries.map { it.inputHash }.toSet().size, "4 distinct inputHashes")
    }

    @Test fun variantCountAboveMaxRejectsLoudBeforeAnyDispatch() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/big".toPath(), title = "big").id
        val engine = OneShotImageGenEngine(tinyPng, providerId = "fake-image")
        val image = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)
        val tool = AigcGenerateTool(image = image)

        val ex = assertFails {
            tool.execute(
                AigcGenerateTool.Input.Image(prompt = "x", projectId = pid.value, variantCount = 1_000),
                ctx(),
            )
        }
        assertTrue(
            "exceeds upper bound" in ex.message.orEmpty(),
            "should mention the upper bound; got: ${ex.message}",
        )
        assertEquals(0, engine.calls)
    }
}
