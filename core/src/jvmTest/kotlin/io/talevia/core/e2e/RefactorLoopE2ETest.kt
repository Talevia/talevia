package io.talevia.core.e2e

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool
import io.talevia.core.tool.builtin.source.UpdateCharacterRefTool
import io.talevia.core.tool.builtin.video.ExportTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end regression for the VISION §6 refactor loop — the flagship
 * workflow every piece of this system exists to support. Wires a real
 * `ToolRegistry` over fake engines and drives:
 *
 *   1. seed: a project with an AIGC-bound clip + matching lockfile entry
 *   2. edit character_ref (the "rename Mei's hair" step)
 *   3. assert the clip goes stale
 *   4. assert ExportTool refuses the render (stale-guard)
 *   5. call `regenerate_stale_clips`
 *   6. assert the clip's assetId flipped + staleness cleared
 *   7. call `export` again — now succeeds
 *
 * Guards: if a future refactor breaks the stale-guard, the
 * regenerate→replace swap, or the baseInputs replay, this test fails
 * before any CI-visible integration does. Single test class so the full
 * loop stays legible in one file.
 */
class RefactorLoopE2ETest {

    private class CountingImageEngine : ImageGenEngine {
        override val providerId: String = "fake"
        var calls: Int = 0
            private set

        override suspend fun generate(request: ImageGenRequest): ImageGenResult {
            calls += 1
            val marker = calls.toByte()
            val bytes = ByteArray(8) { marker }
            return ImageGenResult(
                images = listOf(GeneratedImage(pngBytes = bytes, width = request.width, height = request.height)),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = request.seed,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 1_700_000_000_000L + calls,
                ),
            )
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : MediaBlobWriter {
        override suspend fun writeBlob(bytes: ByteArray, suggestedExtension: String): MediaSource {
            val file = File(rootDir, "${Files.list(rootDir.toPath()).count()}.$suggestedExtension")
            file.writeBytes(bytes)
            return MediaSource.File(file.absolutePath)
        }
    }

    private class FakeVideoEngine : VideoEngine {
        var renderCalls: Int = 0
            private set

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress> = flow {
            renderCalls += 1
            emit(RenderProgress.Started("job"))
            // Write a small stub file so ExportTool's happy path sees a real file.
            File(output.targetPath).writeBytes(byteArrayOf(0, 1, 2))
            emit(RenderProgress.Completed("job", output.targetPath))
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun editCharacterThenRegenerateThenExport() = runTest {
        val tmpDir = createTempDirectory("e2e-refactor").toFile()
        val outputFile = File(tmpDir, "final.mp4")

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val storage = InMemoryMediaStorage()
        val imageEngine = CountingImageEngine()
        val videoEngine = FakeVideoEngine()
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, storage, writer, store))
        registry.register(UpdateCharacterRefTool(store))
        registry.register(RegenerateStaleClipsTool(store, registry))
        registry.register(ExportTool(store, videoEngine))

        // --- Seed: project with one clip bound to "mei". We drive the seed
        // through `generate_image` directly so baseInputs + provenance +
        // source-content snapshot all land authentically (no hand-rolled
        // LockfileEntry).
        val pid = ProjectId("e2e")
        store.upsert(
            "e2e",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = 5.seconds),
            ),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val genTool = registry["generate_image"]!!
        val genResult = genTool.dispatch(
            buildJsonObject {
                put("prompt", "portrait of Mei")
                put("seed", 42L)
                put("projectId", pid.value)
                put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""["mei"]"""))
                put("width", 512)
                put("height", 512)
            },
            ctx(),
        )
        val firstAssetId = JsonConfig.default.parseToJsonElement(
            registry["generate_image"]!!.encodeOutput(genResult).toString(),
        ).let { (it as JsonObject)["assetId"]!!.toString().trim('"') }
        assertEquals(1, imageEngine.calls)

        // Place the generated asset on a video track, binding it to "mei"
        // so the DAG lane picks it up.
        store.mutate(pid) { p ->
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c-1"),
                                    timeRange = TimeRange(0.seconds, 3.seconds),
                                    sourceRange = TimeRange(0.seconds, 3.seconds),
                                    assetId = AssetId(firstAssetId),
                                    sourceBinding = setOf(SourceNodeId("mei")),
                                ),
                            ),
                        ),
                    ),
                    duration = 3.seconds,
                ),
            )
        }

        // --- 1. fresh project exports cleanly (baseline)
        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("outputPath", outputFile.absolutePath)
            },
            ctx(),
        )
        assertEquals(1, videoEngine.renderCalls)

        // --- 2. edit the character — this is the §6 "rename Mei's hair" step
        registry["update_character_ref"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("nodeId", "mei")
                put("visualDescription", "red hair")
            },
            ctx(),
        )

        // --- 3. clip is now stale (source hash drifted from the lockfile snapshot)
        val staleNow = store.get(pid)!!.staleClipsFromLockfile()
        assertEquals(1, staleNow.size)
        assertEquals("c-1", staleNow.single().clipId.value)

        // --- 4. export refuses the stale render; no new engine calls
        val ex = assertFailsWith<IllegalStateException> {
            registry["export"]!!.dispatch(
                buildJsonObject {
                    put("projectId", pid.value)
                    put("outputPath", outputFile.absolutePath)
                },
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("stale"), "export must refuse stale: ${ex.message}")
        assertEquals(1, videoEngine.renderCalls, "stale-guard must block the engine")

        // --- 5. regenerate — one new AIGC call, clip's assetId flips
        registry["regenerate_stale_clips"]!!.dispatch(
            buildJsonObject { put("projectId", pid.value) },
            ctx(),
        )
        assertEquals(2, imageEngine.calls, "regenerate must call the image engine exactly once")

        val project = store.get(pid)!!
        val clip = project.timeline.tracks.first().clips.filterIsInstance<Clip.Video>().single()
        assertTrue(clip.assetId.value != firstAssetId, "clip assetId must flip after regenerate")
        assertEquals(setOf(SourceNodeId("mei")), clip.sourceBinding, "binding must survive swap")
        assertEquals(0, project.staleClipsFromLockfile().size, "nothing should be stale post-regen")

        // --- 6. export now succeeds — stale-guard clears, render runs again
        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("outputPath", outputFile.absolutePath)
            },
            ctx(),
        )
        assertEquals(2, videoEngine.renderCalls, "post-regen export must run the engine")
        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }
}
