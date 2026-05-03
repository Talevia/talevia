package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SynthesizeSpeechToolTest {

    /** Tiny placeholder bytes — engine is fake so no real codec required. */
    private val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    /**
     * Local factory alias of the shared [OneShotTtsEngine] — preserves
     * the `providerId = "fake-openai"` default. The shared base lives
     * in `AigcEngineFakes.kt`.
     */
    private fun FakeTtsEngine(bytes: ByteArray): OneShotTtsEngine =
        OneShotTtsEngine(bytes = bytes, providerId = "fake-openai")

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun persistsAssetIntoBundleAndExposesProvenance() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/tts".toPath()
        val pid = store.createAt(path = bundleRoot, title = "tts").id
        val engine = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            SynthesizeSpeechTool.Input(
                text = "hello world",
                voice = "nova",
                model = "tts-1-hd",
                projectId = pid.value,
            ),
            ctx(),
        )

        val out = result.data
        val project = store.get(pid)!!
        val asset = assertNotNull(project.assets.find { it.id == AssetId(out.assetId) })
        val src = asset.source as MediaSource.BundleFile
        assertTrue(src.relativePath.startsWith("media/"))
        val onDisk = bundleRoot.resolve(src.relativePath)
        assertTrue(fs.exists(onDisk))
        assertEquals(fakeMp3.toList(), fs.read(onDisk) { readByteArray() }.toList())

        assertEquals("fake-openai", out.providerId)
        assertEquals("tts-1-hd", out.modelId)
        assertEquals("nova", out.voice)
        assertEquals("mp3", out.format)
        assertEquals(false, out.cacheHit)

        val req = assertNotNull(engine.lastRequest)
        assertEquals("hello world", req.text)
        assertEquals("nova", req.voice)
        assertEquals("tts-1-hd", req.modelId)
        assertEquals(1.0, req.speed)
    }

    @Test fun secondCallWithIdenticalInputsIsLockfileCacheHit() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/cache".toPath(), title = "cache").id
        val engine = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)
        val input = SynthesizeSpeechTool.Input(
            text = "the quick brown fox",
            voice = "alloy",
            model = "tts-1",
            projectId = pid.value,
        )

        val first = tool.generate(input, ctx())
        assertEquals(false, first.data.cacheHit)
        assertEquals(1, engine.calls)

        val second = tool.generate(input, ctx())
        assertEquals(true, second.data.cacheHit)
        assertEquals(first.data.assetId, second.data.assetId)
        assertEquals(1, engine.calls, "cache hit must not call the engine again")

        // Change voice → miss + new asset.
        val third = tool.generate(input.copy(voice = "echo"), ctx())
        assertEquals(false, third.data.cacheHit)
        assertEquals(2, engine.calls)
        assertTrue(third.data.assetId != first.data.assetId)

        val lockfile = store.get(pid)!!.lockfile
        assertEquals(2, lockfile.entries.size)
    }

    @Test fun changingTextOrSpeedOrFormatBustsTheCache() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/keys".toPath(), title = "keys").id
        val engine = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)
        val base = SynthesizeSpeechTool.Input(text = "one", projectId = pid.value)

        tool.generate(base, ctx()) // warm
        tool.generate(base.copy(text = "two"), ctx())
        tool.generate(base.copy(speed = 1.25), ctx())
        tool.generate(base.copy(format = "wav"), ctx())

        // 4 distinct hashes → 4 engine calls + 4 lockfile entries.
        assertEquals(4, engine.calls)
        assertEquals(4, store.get(pid)!!.lockfile.entries.size)
    }

    @Test fun boundCharacterVoiceIdOverridesExplicitVoice() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/bind".toPath(), title = "bind").id
        store.mutateSource(pid) { src ->
            src.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
            )
        }
        val engine = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            SynthesizeSpeechTool.Input(
                text = "hello",
                voice = "alloy", // caller forgot to update; binding should win.
                projectId = pid.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        assertEquals("nova", result.data.voice)
        assertEquals(listOf("mei"), result.data.appliedConsistencyBindingIds)
        assertEquals("nova", engine.lastRequest?.voice)

        // Lockfile records the binding so stale-clip detection notices future edits.
        val entry = store.get(pid)!!.lockfile.entries.single()
        assertEquals(setOf(SourceNodeId("mei")), entry.sourceBinding)
    }

    @Test fun boundCharacterWithoutVoiceIdFallsBackToExplicitVoice() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/fb".toPath(), title = "fb").id
        store.mutateSource(pid) { src ->
            src.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x"), // no voiceId
            )
        }
        val engine = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            SynthesizeSpeechTool.Input(
                text = "hi",
                voice = "alloy",
                projectId = pid.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        assertEquals("alloy", result.data.voice)
        assertTrue(
            result.data.appliedConsistencyBindingIds.isEmpty(),
            "no voiceId on bound character → nothing was actually 'applied' to voice selection",
        )
        // Binding is still recorded so a future voiceId edit on the character marks the clip stale.
        val entry = store.get(pid)!!.lockfile.entries.single()
        assertTrue(entry.sourceBinding.isEmpty())
    }

    @Test fun twoBoundCharactersWithVoiceIdsFailLoudly() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/ambig".toPath(), title = "ambig").id
        store.mutateSource(pid) { src ->
            src
                .addCharacterRef(
                    SourceNodeId("mei"),
                    CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
                )
                .addCharacterRef(
                    SourceNodeId("jun"),
                    CharacterRefBody(name = "Jun", visualDescription = "y", voiceId = "onyx"),
                )
        }
        val engine = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)

        assertFailsWith<IllegalStateException> {
            tool.generate(
                SynthesizeSpeechTool.Input(
                    text = "hi",
                    projectId = pid.value,
                    consistencyBindingIds = listOf("mei", "jun"),
                ),
                ctx(),
            )
        }
        assertEquals(0, engine.calls, "ambiguous voice binding must fail before hitting the engine")
    }

    @Test fun voiceBindingChangesTheCacheKey() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/vk".toPath(), title = "vk").id
        store.mutateSource(pid) { src ->
            src.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
            )
        }
        val engine = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(engine, FileBundleBlobWriter(store, fs), store)

        // First: bind + different explicit voice — resolved voice is "nova".
        val bound = tool.generate(
            SynthesizeSpeechTool.Input(
                text = "same text",
                voice = "alloy",
                projectId = pid.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )
        // Second: no binding but caller passes the already-resolved voice — should re-use the cache.
        val unbound = tool.generate(
            SynthesizeSpeechTool.Input(
                text = "same text",
                voice = "nova",
                projectId = pid.value,
            ),
            ctx(),
        )
        assertEquals(true, unbound.data.cacheHit, "hash is keyed on resolved voice, not the raw input.voice")
        assertEquals(bound.data.assetId, unbound.data.assetId)
    }

    @Test fun fallbackChainUsesSecondEngineWhenFirstThrows() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/fallback".toPath(), title = "fallback").id
        val primary = FailingTtsEngine(providerId = "fake-primary")
        val secondary = FakeTtsEngine(fakeMp3)
        val tool = SynthesizeSpeechTool(listOf(primary, secondary), FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            SynthesizeSpeechTool.Input(text = "hello", voice = "nova", projectId = pid.value),
            ctx(),
        )
        // Both fakes now share the `calls` counter (cycle-127 fake-extract
        // phase 2 unified naming across OneShot* and Failing* fakes).
        assertEquals(1, primary.calls, "primary must be attempted")
        assertEquals(1, secondary.calls, "secondary must take over after primary throws")
        assertEquals("fake-openai", result.data.providerId, "lockfile + output record the engine that actually produced audio")
    }

    @Test fun fallbackChainExhaustedPropagatesLastFailure() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/fallback-dead".toPath(), title = "dead").id
        val first = FailingTtsEngine(providerId = "dead-primary", message = "first outage")
        val second = FailingTtsEngine(providerId = "dead-secondary", message = "second outage")
        val tool = SynthesizeSpeechTool(listOf(first, second), FileBundleBlobWriter(store, fs), store)

        val ex = assertFailsWith<IllegalStateException> {
            tool.generate(
                SynthesizeSpeechTool.Input(text = "hello", voice = "nova", projectId = pid.value),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("dead-primary"), "aggregate error names every attempted provider")
        assertTrue(ex.message!!.contains("dead-secondary"), "aggregate error names every attempted provider")
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test fun fallbackChainShortCircuitsOnCacheHit() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/fallback-cache".toPath(), title = "cache").id
        val primary = FakeTtsEngine(fakeMp3)
        val secondary = FailingTtsEngine(providerId = "should-not-fire")
        val tool = SynthesizeSpeechTool(listOf(primary, secondary), FileBundleBlobWriter(store, fs), store)

        // First call produces a lockfile entry via the primary.
        tool.generate(
            SynthesizeSpeechTool.Input(text = "hello", voice = "nova", projectId = pid.value),
            ctx(),
        )
        // Second call with identical inputs cache-hits — neither engine runs.
        val second = tool.generate(
            SynthesizeSpeechTool.Input(text = "hello", voice = "nova", projectId = pid.value),
            ctx(),
        )
        assertEquals(true, second.data.cacheHit)
        // Both fakes (OneShotTtsEngine for primary, FailingTtsEngine for
        // secondary) share the unified `calls` counter since the cycle-127
        // fake-extract phase 2.
        assertEquals(1, primary.calls, "cache hit must not re-invoke primary")
        assertEquals(0, secondary.calls, "cache hit must not even probe the fallback chain")
    }

    @Test fun emptyEngineListFailsLoudAtConstruction() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SynthesizeSpeechTool(
                engines = emptyList(),
                bundleBlobWriter = FileBundleBlobWriter(ProjectStoreTestKit.create()),
                projectStore = ProjectStoreTestKit.create(),
            )
        }
        assertTrue(ex.message!!.contains("at least one"), ex.message)
    }
}
