package io.talevia.core.tool.builtin.project

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
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RegenerateStaleClipsToolTest {

    private val tinyPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    /**
     * Image-gen fake that produces distinct bytes per call so each regeneration
     * yields a *different* asset (and therefore a different lockfile entry) even
     * though storage.import would otherwise deduplicate identical sources.
     */
    private class CountingImageEngine : ImageGenEngine {
        override val providerId: String = "fake"
        var calls: Int = 0
            private set

        override suspend fun generate(request: ImageGenRequest): ImageGenResult {
            calls += 1
            val marker = calls.toByte()
            // Distinct bytes per call so MediaStorage treats them as separate assets.
            val bytes = ByteArray(4) { marker }
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

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun regeneratesEachStaleClipAndSwapsAssetIdOnTimeline() = runTest {
        val tmpDir = createTempDirectory("regen-test").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val storage = InMemoryMediaStorage()
        val engine = CountingImageEngine()
        val writer = FakeBlobWriter(tmpDir)
        val pid = ProjectId("p-regen")
        val clipId = ClipId("c-1")

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(engine, storage, writer, store))
        registry.register(RegenerateStaleClipsTool(store, registry))

        // Seed: character_ref + a clip bound to it + a lockfile entry with baseInputs.
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = clipId,
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId("a-original"),
                                    sourceBinding = setOf(SourceNodeId("mei")),
                                ),
                            ),
                        ),
                    ),
                    duration = 2.seconds,
                ),
            ),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        val originalInputs = buildJsonObject {
            put("prompt", "portrait of Mei")
            put("model", "gpt-image-1")
            put("width", 512)
            put("height", 512)
            put("seed", 42L)
            put("projectId", pid.value)
            put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""["mei"]"""))
        }
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "original-hash",
                        toolId = "generate_image",
                        assetId = AssetId("a-original"),
                        provenance = GenerationProvenance(
                            providerId = "fake",
                            modelId = "gpt-image-1",
                            modelVersion = null,
                            seed = 42L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                        baseInputs = originalInputs,
                    ),
                ),
            )
        }

        // Edit character — clip becomes stale.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red hair"),
                    ),
                )
            }
        }

        // Run regenerate.
        val tool = RegenerateStaleClipsTool(store, registry)
        val out = tool.execute(RegenerateStaleClipsTool.Input(projectId = pid.value), ctx()).data
        assertEquals(1, out.totalStale)
        assertEquals(1, out.regenerated.size)
        assertEquals(0, out.skipped.size)
        val regen = out.regenerated.single()
        assertEquals(clipId.value, regen.clipId)
        assertEquals("generate_image", regen.toolId)
        assertEquals("a-original", regen.previousAssetId)
        assertTrue(regen.newAssetId != "a-original", "regeneration must produce a new asset id")

        // Verify timeline swapped + fresh bindings populated.
        val project = store.get(pid)!!
        val clip = project.timeline.tracks.first().clips.filterIsInstance<Clip.Video>().single()
        assertEquals(regen.newAssetId, clip.assetId.value)
        assertEquals(setOf(SourceNodeId("mei")), clip.sourceBinding)

        // Verify the regeneration used the *current* source — effective prompt should
        // fold "red hair" now, not "teal hair". We check the new lockfile entry.
        val newEntry = project.lockfile.entries.last()
        val newHash = project.source.deepContentHashOf(SourceNodeId("mei"))
        assertEquals(mapOf(SourceNodeId("mei") to newHash), newEntry.sourceContentHashes)
        assertTrue(newHash != originalHash, "source hash must differ after edit")
        assertEquals(1, engine.calls, "engine must have been invoked exactly once for the single stale clip")
    }

    @Test fun clipIdsFilterRegeneratesOnlyListedClips() = runTest {
        val tmpDir = createTempDirectory("regen-filter").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val storage = InMemoryMediaStorage()
        val engine = CountingImageEngine()
        val writer = FakeBlobWriter(tmpDir)
        val pid = ProjectId("p-filter")

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(engine, storage, writer, store))
        registry.register(RegenerateStaleClipsTool(store, registry))

        // Two clips, both stale, both bound to "mei" via lockfile snapshots.
        val clips = listOf(
            Clip.Video(
                id = ClipId("c-keep"),
                timeRange = TimeRange(0.seconds, 1.seconds),
                sourceRange = TimeRange(0.seconds, 1.seconds),
                assetId = AssetId("a-keep"),
                sourceBinding = setOf(SourceNodeId("mei")),
            ),
            Clip.Video(
                id = ClipId("c-skip"),
                timeRange = TimeRange(1.seconds, 1.seconds),
                sourceRange = TimeRange(0.seconds, 1.seconds),
                assetId = AssetId("a-skip"),
                sourceBinding = setOf(SourceNodeId("mei")),
            ),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(Track.Video(id = TrackId("v"), clips = clips)),
                    duration = 2.seconds,
                ),
            ),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        val originalInputs = buildJsonObject {
            put("prompt", "portrait of Mei")
            put("model", "gpt-image-1")
            put("width", 512)
            put("height", 512)
            put("seed", 42L)
            put("projectId", pid.value)
            put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""["mei"]"""))
        }
        for (clip in clips) {
            store.mutate(pid) { p ->
                p.copy(
                    lockfile = p.lockfile.append(
                        LockfileEntry(
                            inputHash = "h-${clip.id.value}",
                            toolId = "generate_image",
                            assetId = clip.assetId,
                            provenance = GenerationProvenance(
                                providerId = "fake",
                                modelId = "gpt-image-1",
                                modelVersion = null,
                                seed = 42L,
                                parameters = JsonObject(emptyMap()),
                                createdAtEpochMs = 0L,
                            ),
                            sourceBinding = setOf(SourceNodeId("mei")),
                            sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                            baseInputs = originalInputs,
                        ),
                    ),
                )
            }
        }
        // Edit → both clips become stale.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red"),
                    ),
                )
            }
        }

        val tool = RegenerateStaleClipsTool(store, registry)
        val out = tool.execute(
            RegenerateStaleClipsTool.Input(projectId = pid.value, clipIds = listOf("c-keep")),
            ctx(),
        ).data

        // Only c-keep in the reported `totalStale` because the filter narrows the
        // intake, but the semantics are "of the clips you asked about, how many
        // were stale?" — 1 here. c-skip remains stale on the project but was
        // not touched.
        assertEquals(1, out.totalStale)
        assertEquals(1, out.regenerated.size)
        assertEquals("c-keep", out.regenerated.single().clipId)
        assertEquals(1, engine.calls, "only one AIGC call despite two stale clips")

        val project = store.get(pid)!!
        val clipMap = project.timeline.tracks.first().clips.associateBy { it.id.value }
        assertEquals("a-skip", (clipMap["c-skip"] as Clip.Video).assetId.value, "unlisted clip must be untouched")
        assertTrue((clipMap["c-keep"] as Clip.Video).assetId.value != "a-keep")
    }

    @Test fun skipsLegacyEntriesWithoutBaseInputs() = runTest {
        val tmpDir = createTempDirectory("regen-legacy").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val storage = InMemoryMediaStorage()
        val engine = CountingImageEngine()
        val writer = FakeBlobWriter(tmpDir)
        val pid = ProjectId("p-legacy")
        val clipId = ClipId("c-1")

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(engine, storage, writer, store))
        registry.register(RegenerateStaleClipsTool(store, registry))

        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = clipId,
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId("a-legacy"),
                                    sourceBinding = setOf(SourceNodeId("mei")),
                                ),
                            ),
                        ),
                    ),
                    duration = 2.seconds,
                ),
            ),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "h",
                        toolId = "generate_image",
                        assetId = AssetId("a-legacy"),
                        provenance = GenerationProvenance(
                            providerId = "fake",
                            modelId = "gpt-image-1",
                            modelVersion = null,
                            seed = 1L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                        // baseInputs intentionally empty — legacy entry
                    ),
                ),
            )
        }
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red"),
                    ),
                )
            }
        }

        val tool = RegenerateStaleClipsTool(store, registry)
        val out = tool.execute(RegenerateStaleClipsTool.Input(projectId = pid.value), ctx()).data
        assertEquals(1, out.totalStale)
        assertEquals(0, out.regenerated.size)
        assertEquals(1, out.skipped.size)
        assertTrue(out.skipped.single().reason.contains("legacy"))
        assertEquals(0, engine.calls, "engine must not be invoked when all stale clips are legacy")
    }

    @Test fun emptyProjectReturnsZeroWithoutDispatching() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val registry = ToolRegistry()
        val pid = ProjectId("p-empty")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))

        val tool = RegenerateStaleClipsTool(store, registry)
        val out = tool.execute(RegenerateStaleClipsTool.Input(projectId = pid.value), ctx()).data
        assertEquals(0, out.totalStale)
        assertEquals(0, out.regenerated.size)
    }

    @Test fun skipsPinnedEntriesWithoutDispatching() = runTest {
        val tmpDir = createTempDirectory("regen-pinned").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val storage = InMemoryMediaStorage()
        val engine = CountingImageEngine()
        val writer = FakeBlobWriter(tmpDir)
        val pid = ProjectId("p-pinned")
        val clipId = ClipId("c-hero")

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(engine, storage, writer, store))
        registry.register(RegenerateStaleClipsTool(store, registry))

        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = clipId,
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId("a-hero"),
                                    sourceBinding = setOf(SourceNodeId("mei")),
                                ),
                            ),
                        ),
                    ),
                    duration = 2.seconds,
                ),
            ),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        val originalInputs = buildJsonObject {
            put("prompt", "portrait of Mei")
            put("model", "gpt-image-1")
            put("width", 512)
            put("height", 512)
            put("seed", 42L)
            put("projectId", pid.value)
            put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""["mei"]"""))
        }
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "pinned-hash",
                        toolId = "generate_image",
                        assetId = AssetId("a-hero"),
                        provenance = GenerationProvenance(
                            providerId = "fake",
                            modelId = "gpt-image-1",
                            modelVersion = null,
                            seed = 42L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                        baseInputs = originalInputs,
                        pinned = true,
                    ),
                ),
            )
        }
        // Edit mei — the clip is now stale.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red"),
                    ),
                )
            }
        }

        val tool = RegenerateStaleClipsTool(store, registry)
        val out = tool.execute(RegenerateStaleClipsTool.Input(projectId = pid.value), ctx()).data
        // VISION §3.1: pinned entries are user-intent hero shots. Stale-but-frozen.
        assertEquals(1, out.totalStale)
        assertEquals(0, out.regenerated.size)
        assertEquals(1, out.skipped.size)
        assertEquals("pinned", out.skipped.single().reason)
        assertEquals(0, engine.calls, "engine must not run for a pinned stale clip")

        // Timeline left untouched.
        val project = store.get(pid)!!
        val clip = project.timeline.tracks.first().clips.filterIsInstance<Clip.Video>().single()
        assertEquals("a-hero", clip.assetId.value)
    }
}
