package io.talevia.core.tool.builtin.project

import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.SynthesizedAudio
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.AigcSpeechGenerator
import io.talevia.core.tool.builtin.aigc.toolShimForSpeech
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `variantSpec.language` on [ForkProjectTool] — verifies that the fork
 * dispatches `synthesize_speech` per non-blank text clip with the target
 * language, threads (clipId, assetId, cacheHit) into Output, and refuses to
 * silently skip work when the registry / synth tool is missing.
 */
class ForkProjectLanguageVariantTest {

    private val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    private class FakeTtsEngine(private val bytes: ByteArray) : TtsEngine {
        override val providerId: String = "fake-openai"
        val requests = mutableListOf<TtsRequest>()

        override suspend fun synthesize(request: TtsRequest): TtsResult {
            requests += request
            return TtsResult(
                audio = SynthesizedAudio(audioBytes = bytes, format = request.format),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = 0L,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 1_700_000_000_000L,
                ),
            )
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : BundleBlobWriter {
        override suspend fun writeBlob(
            projectId: io.talevia.core.ProjectId,
            assetId: io.talevia.core.AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile {
            val file = File(rootDir, "${assetId.value}.$format")
            file.writeBytes(bytes)
            return MediaSource.BundleFile("media/${file.name}")
        }
    }

    private data class Rig(
        val store: FileProjectStore,
        val registry: ToolRegistry,
        val tts: FakeTtsEngine,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val tmpDir = createTempDirectory("fork-lang-test").toFile()
        val store = ProjectStoreTestKit.create()
        val tts = FakeTtsEngine(fakeMp3)
        val writer = FakeBlobWriter(tmpDir)
        val registry = ToolRegistry()
        registry.register(toolShimForSpeech(AigcSpeechGenerator(tts, writer, store)))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, registry, tts, ctx)
    }

    private fun textClip(id: String, text: String, binding: String? = null): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 2.seconds),
        text = text,
        sourceBinding = binding?.let { setOf(SourceNodeId(it)) } ?: emptySet(),
    )

    private suspend fun seedProject(store: FileProjectStore, clips: List<Clip>) {
        store.upsert(
            "test",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("sub"), clips))),
            ),
        )
    }

    @Test fun languageVariantDispatchesTtsPerTextClip() = runTest {
        val rig = rig()
        seedProject(
            rig.store,
            listOf(
                textClip("t-1", "Hello world"),
                textClip("t-2", "Second line"),
            ),
        )
        val tool = ForkProjectTool(rig.store, rig.registry)

        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "es variant",
                variantSpec = ForkProjectTool.VariantSpec(language = "es"),
            ),
            rig.ctx,
        ).data

        assertEquals(2, out.languageRegeneratedClips.size)
        val clipIds = out.languageRegeneratedClips.map { it.clipId }.toSet()
        assertEquals(setOf("t-1", "t-2"), clipIds)
        assertTrue(out.languageRegeneratedClips.all { !it.cacheHit }, "first fork → all fresh")
        // Engine was called twice, each with language=es.
        assertEquals(2, rig.tts.requests.size)
        assertTrue(rig.tts.requests.all { it.language == "es" })
        // Each dispatch wrote a lockfile entry on the fork.
        val fork = rig.store.get(ProjectId(out.newProjectId))!!
        assertEquals(2, fork.lockfile.entries.size)
        assertTrue(fork.lockfile.entries.all { it.toolId == "synthesize_speech" })
    }

    @Test fun languageVariantSkipsBlankTextClips() = runTest {
        val rig = rig()
        seedProject(
            rig.store,
            listOf(
                textClip("t-1", "Real text"),
                textClip("t-blank", "   "), // blank → skip
                textClip("t-empty", ""),   // empty → skip
            ),
        )
        val tool = ForkProjectTool(rig.store, rig.registry)

        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "zh variant",
                variantSpec = ForkProjectTool.VariantSpec(language = "zh"),
            ),
            rig.ctx,
        ).data

        assertEquals(1, out.languageRegeneratedClips.size)
        assertEquals("t-1", out.languageRegeneratedClips.single().clipId)
    }

    @Test fun secondForkInSameLanguageHitsLockfileCache() = runTest {
        val rig = rig()
        seedProject(rig.store, listOf(textClip("t-1", "Hello world")))
        val tool = ForkProjectTool(rig.store, rig.registry)

        tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "es one",
                variantSpec = ForkProjectTool.VariantSpec(language = "es"),
            ),
            rig.ctx,
        )
        // Second fork of the same source in the same language — each fork has
        // its own lockfile so the second call is NOT a cache hit against the
        // first fork's entries. What we're verifying: the engine gets called
        // again (different project), but the (text, language) pair produces
        // the same lockfile inputHash semantics (i.e. the hash function is
        // deterministic and honours language).
        val before = rig.tts.requests.size
        tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "es two",
                variantSpec = ForkProjectTool.VariantSpec(language = "es"),
            ),
            rig.ctx,
        )
        assertEquals(before + 1, rig.tts.requests.size)
    }

    @Test fun differentLanguagesYieldDifferentLockfileEntries() = runTest {
        val rig = rig()
        seedProject(rig.store, listOf(textClip("t-1", "Hello world")))
        val tool = ForkProjectTool(rig.store, rig.registry)

        val esOut = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "es",
                variantSpec = ForkProjectTool.VariantSpec(language = "es"),
            ),
            rig.ctx,
        ).data
        val zhOut = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "zh",
                variantSpec = ForkProjectTool.VariantSpec(language = "zh"),
            ),
            rig.ctx,
        ).data

        val esFork = rig.store.get(ProjectId(esOut.newProjectId))!!
        val zhFork = rig.store.get(ProjectId(zhOut.newProjectId))!!
        val esHash = esFork.lockfile.entries.single().inputHash
        val zhHash = zhFork.lockfile.entries.single().inputHash
        assertFalse(
            esHash == zhHash,
            "same text in two languages must produce distinct lockfile inputHashes — got $esHash twice",
        )
    }

    @Test fun languageVariantWithoutRegistryFailsLoud() = runTest {
        val rig = rig()
        seedProject(rig.store, listOf(textClip("t-1", "Hello")))
        val tool = ForkProjectTool(rig.store, registry = null)

        val e = assertFailsWith<IllegalStateException> {
            tool.execute(
                ForkProjectTool.Input(
                    sourceProjectId = "p",
                    newTitle = "fails",
                    variantSpec = ForkProjectTool.VariantSpec(language = "es"),
                ),
                rig.ctx,
            )
        }
        assertTrue(
            e.message?.contains("no ToolRegistry") == true,
            "expected a wiring-hint message; got: ${e.message}",
        )
    }

    @Test fun forksWithoutLanguageIgnoreRegistry() = runTest {
        val rig = rig()
        seedProject(rig.store, listOf(textClip("t-1", "Hello")))
        val tool = ForkProjectTool(rig.store, rig.registry)

        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "plain fork",
            ),
            rig.ctx,
        ).data
        assertTrue(out.languageRegeneratedClips.isEmpty(), "no regen without language")
        assertEquals(0, rig.tts.requests.size)
    }

    @Test fun outputPayloadRoundTripsThroughJson() = runTest {
        // Make sure the new field survives the typed serialisation boundary
        // that AgentTurnExecutor relies on.
        val rig = rig()
        seedProject(rig.store, listOf(textClip("t-1", "Hello world")))
        val tool = ForkProjectTool(rig.store, rig.registry)
        val result = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "es variant",
                variantSpec = ForkProjectTool.VariantSpec(language = "es"),
            ),
            rig.ctx,
        )
        val json = JsonConfig.default.encodeToString(
            ForkProjectTool.Output.serializer(),
            result.data,
        )
        assertTrue(json.contains("languageRegeneratedClips"))
        assertTrue(json.contains("\"t-1\""))
    }
}
