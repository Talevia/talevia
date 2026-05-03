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

/**
 * `debt-aigc-tool-consolidation-phase3a-introduce-generator-interface`
 * (cycle 34). Phase 3a is pure addition — new sealed-interface family +
 * 4 `ToolBacked*Generator` wrappers — with no dispatcher changes. This
 * suite is a structural smoke test: the four wrappers must delegate to
 * the underlying `Tool<I, O>` impl correctly so that phase 3b can
 * switch the dispatcher without surprises.
 *
 * Each test exercises one wrapper end-to-end:
 *   - construct the `Tool<I, O>` over a fake provider,
 *   - wrap it via the `ToolBacked*Generator`,
 *   - invoke `generator.generate(...)`,
 *   - assert the wrapped `Tool<I, O>` was called exactly once + the
 *     ToolResult round-trips the inner Output.
 *
 * Image's `generateBatch` path is exercised separately in
 * `AigcGenerateToolTest.nativeBatchEngineFiresOneProviderCallForVariantCount4`
 * (cycle 33) — the wrapper just delegates so adding a fourth assertion
 * here would be redundant.
 */
class AigcGeneratorTest {

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

    @Test fun toolBackedImageGeneratorDelegatesToInnerTool() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/img-gen".toPath(), title = "img-gen").id
        val engine = OneShotImageGenEngine(tinyPng, providerId = "fake-image-gen")
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)
        val generator: ImageAigcGenerator = ToolBackedImageGenerator(tool)

        // Engine reflects the inner tool's engine — phase 3b's
        // dispatcher reads this for the batch-vs-sequential decision.
        assertEquals(engine, generator.engine)

        val result = generator.generate(
            GenerateImageTool.Input(prompt = "a cat", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls, "wrapper must call inner engine exactly once")
        assertEquals("fake-image-gen", result.data.providerId, "Output must round-trip through the wrapper")
    }

    @Test fun toolBackedVideoGeneratorDelegatesToInnerTool() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/vid-gen".toPath(), title = "vid-gen").id
        val engine = OneShotVideoGenEngine(tinyMp4, providerId = "fake-video-gen")
        val tool = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)
        val generator: VideoAigcGenerator = ToolBackedVideoGenerator(tool)

        val result = generator.generate(
            GenerateVideoTool.Input(prompt = "a chase", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls)
        assertEquals("fake-video-gen", result.data.providerId)
    }

    @Test fun toolBackedMusicGeneratorDelegatesToInnerTool() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/mus-gen".toPath(), title = "mus-gen").id
        val engine = OneShotMusicGenEngine(tinyMusic, providerId = "fake-music-gen")
        val tool = GenerateMusicTool(engine, FileBundleBlobWriter(store, fs), store)
        val generator: MusicAigcGenerator = ToolBackedMusicGenerator(tool)

        val result = generator.generate(
            GenerateMusicTool.Input(prompt = "lofi beats", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls)
        assertEquals("fake-music-gen", result.data.providerId)
    }

    @Test fun toolBackedSpeechGeneratorDelegatesToInnerTool() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/spk-gen".toPath(), title = "spk-gen").id
        val engine = OneShotTtsEngine(tinyMp3, providerId = "fake-tts-gen")
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)
        val generator: SpeechAigcGenerator = ToolBackedSpeechGenerator(tool)

        val result = generator.generate(
            SynthesizeSpeechTool.Input(text = "hello world", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls)
        assertEquals("fake-tts-gen", result.data.providerId)
    }

    @Test fun allFourGeneratorsAreAigcGeneratorSubtype() {
        // Compile-time guard for the sealed-interface family. Each
        // ToolBacked*Generator is constructible (signatures stable) and
        // assigns to AigcGenerator without casts. If a refactor breaks
        // the seal — e.g. dropping `: AigcGenerator` from one sub-
        // interface — this fails compilation, which is the point.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val image: AigcGenerator = ToolBackedImageGenerator(
            GenerateImageTool(OneShotImageGenEngine(tinyPng), FileBundleBlobWriter(store, fs), store),
        )
        val video: AigcGenerator = ToolBackedVideoGenerator(
            GenerateVideoTool(OneShotVideoGenEngine(tinyMp4), FileBundleBlobWriter(store, fs), store),
        )
        val music: AigcGenerator = ToolBackedMusicGenerator(
            GenerateMusicTool(OneShotMusicGenEngine(tinyMusic), FileBundleBlobWriter(store, fs), store),
        )
        val speech: AigcGenerator = ToolBackedSpeechGenerator(
            SynthesizeSpeechTool(OneShotTtsEngine(tinyMp3), FileBundleBlobWriter(store, fs), store),
        )
        val all = listOf(image, video, music, speech)
        assertEquals(4, all.size)
        // All four are distinct concrete types — no accidental fold.
        assertEquals(4, all.map { it::class }.toSet().size)
    }
}
