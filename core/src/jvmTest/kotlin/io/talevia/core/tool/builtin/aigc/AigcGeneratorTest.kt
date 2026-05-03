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
 * Pins the `AigcGenerator` sealed-interface family contract:
 * `AigcImageGenerator` / `AigcVideoGenerator` / `AigcMusicGenerator` /
 * `AigcSpeechGenerator` each implement their per-kind sub-interface
 * (`ImageAigcGenerator` / `VideoAigcGenerator` / `MusicAigcGenerator` /
 * `SpeechAigcGenerator`) directly — no adapter layer.
 *
 * Phase 3a (cycle 34) introduced the family + 4 `ToolBacked*Generator`
 * adapters; phase 3b (cycle 38) routed the dispatcher through the
 * interfaces; phase 3c-1 (cycle 39) collapsed the adapter layer by
 * having the underlying classes implement the interfaces directly. This
 * suite, originally written to pin the adapter delegation, now pins the
 * direct implementation: each `Generate*Tool.generate(...)` invocation
 * exercises the same engine call exactly once and round-trips the
 * Output. Image's `generateBatch` path is exercised separately in
 * `AigcGenerateToolTest.nativeBatchEngineFiresOneProviderCallForVariantCount4`
 * (cycle 33) — adding it here would be redundant.
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

    @Test fun generateImageToolImplementsImageAigcGeneratorDirectly() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/img-gen".toPath(), title = "img-gen").id
        val engine = OneShotImageGenEngine(tinyPng, providerId = "fake-image-gen")
        val generator: ImageAigcGenerator = AigcImageGenerator(engine, FileBundleBlobWriter(store, fs), store)

        // Engine reflects the inner tool's engine — phase 3b's
        // dispatcher reads this for the batch-vs-sequential decision.
        assertEquals(engine, generator.engine)

        val result = generator.generate(
            AigcImageGenerator.Input(prompt = "a cat", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls, "concrete impl must call inner engine exactly once")
        assertEquals("fake-image-gen", result.data.providerId, "Output round-trips through the generate() path")
    }

    @Test fun generateVideoToolImplementsVideoAigcGeneratorDirectly() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/vid-gen".toPath(), title = "vid-gen").id
        val engine = OneShotVideoGenEngine(tinyMp4, providerId = "fake-video-gen")
        val generator: VideoAigcGenerator = AigcVideoGenerator(engine, FileBundleBlobWriter(store, fs), store)

        val result = generator.generate(
            AigcVideoGenerator.Input(prompt = "a chase", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls)
        assertEquals("fake-video-gen", result.data.providerId)
    }

    @Test fun generateMusicToolImplementsMusicAigcGeneratorDirectly() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/mus-gen".toPath(), title = "mus-gen").id
        val engine = OneShotMusicGenEngine(tinyMusic, providerId = "fake-music-gen")
        val generator: MusicAigcGenerator = AigcMusicGenerator(engine, FileBundleBlobWriter(store, fs), store)

        val result = generator.generate(
            AigcMusicGenerator.Input(prompt = "lofi beats", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls)
        assertEquals("fake-music-gen", result.data.providerId)
    }

    @Test fun synthesizeSpeechToolImplementsSpeechAigcGeneratorDirectly() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/spk-gen".toPath(), title = "spk-gen").id
        val engine = OneShotTtsEngine(tinyMp3, providerId = "fake-tts-gen")
        val generator: SpeechAigcGenerator = AigcSpeechGenerator(engine, FileBundleBlobWriter(store, fs), store)

        val result = generator.generate(
            AigcSpeechGenerator.Input(text = "hello world", projectId = pid.value),
            ctx(),
        )
        assertEquals(1, engine.calls)
        assertEquals("fake-tts-gen", result.data.providerId)
    }

    @Test fun allFourGeneratorsAreAigcGeneratorSubtype() {
        // Compile-time guard for the sealed-interface family. Each
        // concrete `Generate*Tool` is assignable to `AigcGenerator`
        // without casts. If a refactor breaks the seal — e.g. dropping
        // `: AigcGenerator` from one sub-interface — this fails
        // compilation, which is the point.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val image: AigcGenerator = AigcImageGenerator(OneShotImageGenEngine(tinyPng), FileBundleBlobWriter(store, fs), store)
        val video: AigcGenerator = AigcVideoGenerator(OneShotVideoGenEngine(tinyMp4), FileBundleBlobWriter(store, fs), store)
        val music: AigcGenerator = AigcMusicGenerator(OneShotMusicGenEngine(tinyMusic), FileBundleBlobWriter(store, fs), store)
        val speech: AigcGenerator = AigcSpeechGenerator(OneShotTtsEngine(tinyMp3), FileBundleBlobWriter(store, fs), store)
        val all = listOf(image, video, music, speech)
        assertEquals(4, all.size)
        // All four are distinct concrete types — no accidental fold.
        assertEquals(4, all.map { it::class }.toSet().size)
    }
}
