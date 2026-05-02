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
 * `GenerateImageToolTest` / `GenerateVideoToolTest` /
 * `GenerateMusicToolTest` / `SynthesizeSpeechToolTest` — this suite
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
        val image = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)
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
        assertEquals(1, engine.calls, "inner GenerateImageTool was dispatched exactly once")
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
        val video = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)
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

    @Test fun speechKindMapsTextThroughPromptField() = runTest {
        // For speech: the dispatcher's `prompt` carries the spoken text;
        // it gets relayed to SynthesizeSpeechTool.Input.text. Verify the
        // round-trip preserves voice / format and surfaces the speech-
        // specific output fields.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/spk".toPath(), title = "spk").id
        val engine = OneShotTtsEngine(tinyMp3, providerId = "fake-tts")
        val speech = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)
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
        val image = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)
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
}
