package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the three reference lanes (clip / lockfile / filter) and the
 * catalog-difference result shape for `find_unreferenced_assets`.
 */
class FindUnreferencedAssetsToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newStore(): SqlDelightProjectStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightProjectStore(TaleviaDb(driver))
    }

    private fun videoAsset(id: String, width: Int = 1920, height: Int = 1080): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(
            duration = 5.seconds,
            resolution = Resolution(width, height),
            videoCodec = "h264",
        ),
    )

    private fun lutAsset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.cube"),
        // LUTs carry no codec metadata — classifier falls back to "image".
        metadata = MediaMetadata(duration = kotlin.time.Duration.ZERO),
    )

    private fun videoClip(id: String, asset: AssetId, filters: List<Filter> = emptyList()): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(0.seconds, 2.seconds),
            sourceRange = TimeRange(0.seconds, 2.seconds),
            assetId = asset,
            filters = filters,
        )

    private fun fakeProvenance(): GenerationProvenance = GenerationProvenance(
        providerId = "fake",
        modelId = "fake-model",
        modelVersion = null,
        seed = 1L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private suspend fun seed(
        store: SqlDelightProjectStore,
        pid: ProjectId,
        assets: List<MediaAsset> = emptyList(),
        clips: List<Clip.Video> = emptyList(),
        lockfile: Lockfile = Lockfile.EMPTY,
    ) {
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v0"), clips))),
                assets = assets,
                lockfile = lockfile,
            ),
        )
    }

    @Test fun emptyCatalogReturnsEmptyList() = runTest {
        val store = newStore()
        val pid = ProjectId("p-empty")
        seed(store, pid, assets = emptyList(), clips = emptyList())

        val out = FindUnreferencedAssetsTool(store)
            .execute(FindUnreferencedAssetsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.totalAssets)
        assertEquals(0, out.referencedCount)
        assertEquals(0, out.unreferencedCount)
        assertTrue(out.unreferenced.isEmpty())
    }

    @Test fun allAssetsReferencedByClipsReturnsEmptyList() = runTest {
        val store = newStore()
        val pid = ProjectId("p-full")
        val a1 = videoAsset("a-1")
        val a2 = videoAsset("a-2")
        seed(
            store,
            pid,
            assets = listOf(a1, a2),
            clips = listOf(videoClip("c-1", a1.id), videoClip("c-2", a2.id)),
        )

        val out = FindUnreferencedAssetsTool(store)
            .execute(FindUnreferencedAssetsTool.Input(pid.value), ctx()).data

        assertEquals(2, out.totalAssets)
        assertEquals(2, out.referencedCount)
        assertEquals(0, out.unreferencedCount)
        assertTrue(out.unreferenced.isEmpty())
    }

    @Test fun oneAssetUnreferencedSurfacesInList() = runTest {
        val store = newStore()
        val pid = ProjectId("p-one-unused")
        val used = videoAsset("a-used")
        val unused = videoAsset("a-unused", width = 3840, height = 2160)
        seed(
            store,
            pid,
            assets = listOf(used, unused),
            clips = listOf(videoClip("c-1", used.id)),
        )

        val out = FindUnreferencedAssetsTool(store)
            .execute(FindUnreferencedAssetsTool.Input(pid.value), ctx()).data

        assertEquals(2, out.totalAssets)
        assertEquals(1, out.referencedCount)
        assertEquals(1, out.unreferencedCount)
        val summary = out.unreferenced.single()
        assertEquals("a-unused", summary.assetId)
        assertEquals(3840, summary.widthPx)
        assertEquals(2160, summary.heightPx)
        assertEquals("video", summary.kind)
        assertEquals(5.0, summary.durationSeconds)
    }

    @Test fun lockfileOnlyReferenceCountsAsReferenced() = runTest {
        val store = newStore()
        val pid = ProjectId("p-lockfile-only")
        val asset = videoAsset("a-generated")
        // No clips reference the asset, but a lockfile entry does (e.g. the agent
        // generated it, the clip has since been deleted, and we still want to keep
        // the provenance trail).
        seed(
            store,
            pid,
            assets = listOf(asset),
            clips = emptyList(),
            lockfile = Lockfile.EMPTY.append(
                LockfileEntry(
                    inputHash = "h1",
                    toolId = "generate_image",
                    assetId = asset.id,
                    provenance = fakeProvenance(),
                ),
            ),
        )

        val out = FindUnreferencedAssetsTool(store)
            .execute(FindUnreferencedAssetsTool.Input(pid.value), ctx()).data

        assertEquals(1, out.totalAssets)
        assertEquals(1, out.referencedCount)
        assertEquals(0, out.unreferencedCount)
        assertTrue(out.unreferenced.isEmpty())
    }

    @Test fun filterAssetReferenceCountsAsReferenced() = runTest {
        val store = newStore()
        val pid = ProjectId("p-lut")
        val footage = videoAsset("a-footage")
        val lut = lutAsset("a-lut")
        // Clip plays `footage` and applies a LUT filter backed by `lut`.
        seed(
            store,
            pid,
            assets = listOf(footage, lut),
            clips = listOf(
                videoClip(
                    "c-1",
                    footage.id,
                    filters = listOf(Filter(name = "lut", assetId = lut.id)),
                ),
            ),
        )

        val out = FindUnreferencedAssetsTool(store)
            .execute(FindUnreferencedAssetsTool.Input(pid.value), ctx()).data

        assertEquals(2, out.totalAssets)
        assertEquals(2, out.referencedCount)
        assertEquals(0, out.unreferencedCount)
        assertTrue(out.unreferenced.isEmpty())
    }

    @Test fun mixedCaseWithMultipleUnreferenced() = runTest {
        val store = newStore()
        val pid = ProjectId("p-mixed")
        // 5 assets total:
        //   a-clipped: on a clip → referenced
        //   a-locked: in lockfile only → referenced
        //   a-lut: filter on a clip → referenced
        //   a-orphan-1, a-orphan-2: nothing → unreferenced (the only two to surface)
        val aClipped = videoAsset("a-clipped")
        val aLocked = videoAsset("a-locked")
        val aLut = lutAsset("a-lut")
        val aOrphan1 = videoAsset("a-orphan-1")
        val aOrphan2 = videoAsset("a-orphan-2")
        seed(
            store,
            pid,
            assets = listOf(aClipped, aLocked, aLut, aOrphan1, aOrphan2),
            clips = listOf(
                videoClip(
                    "c-1",
                    aClipped.id,
                    filters = listOf(Filter(name = "lut", assetId = aLut.id)),
                ),
            ),
            lockfile = Lockfile.EMPTY.append(
                LockfileEntry(
                    inputHash = "h1",
                    toolId = "generate_image",
                    assetId = aLocked.id,
                    provenance = fakeProvenance(),
                ),
            ),
        )

        val out = FindUnreferencedAssetsTool(store)
            .execute(FindUnreferencedAssetsTool.Input(pid.value), ctx()).data

        assertEquals(5, out.totalAssets)
        assertEquals(3, out.referencedCount)
        assertEquals(2, out.unreferencedCount)
        val ids = out.unreferenced.map { it.assetId }.toSet()
        assertEquals(setOf("a-orphan-1", "a-orphan-2"), ids)
    }

    @Test fun missingProjectThrows() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            FindUnreferencedAssetsTool(store)
                .execute(FindUnreferencedAssetsTool.Input("does-not-exist"), ctx())
        }
        assertTrue(ex.message!!.contains("does-not-exist"), ex.message)
    }
}
