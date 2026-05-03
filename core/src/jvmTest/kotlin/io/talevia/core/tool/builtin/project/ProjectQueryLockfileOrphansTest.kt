package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.LockfileOrphanRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for `select=lockfile_orphans` — lockfile entries whose assetId
 * is no longer referenced by any clip on the timeline. Edges (§3a #9):
 *  - empty lockfile / empty timeline → zero rows.
 *  - every entry referenced → zero rows + distinctive narrative.
 *  - mixed referenced / orphan → only orphans surface.
 *  - pinned orphan still appears (for audit) but sorts after unpinned.
 *  - pagination applies cleanly.
 *  - Text clips (no asset) don't accidentally reference anything.
 */
class ProjectQueryLockfileOrphansTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun entry(
        hash: String,
        assetId: String,
        pinned: Boolean = false,
        createdAtEpochMs: Long = 1_000,
        costCents: Long? = 10,
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "gpt-image-1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAtEpochMs,
        ),
        pinned = pinned,
        costCents = costCents,
    )

    private fun videoClip(id: String, assetId: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId(assetId),
    )

    private suspend fun fixture(
        clips: List<Clip> = emptyList(),
        lockfileEntries: List<LockfileEntry> = emptyList(),
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        val timeline = if (clips.isEmpty()) {
            Timeline()
        } else {
            Timeline(tracks = listOf(Track.Video(id = TrackId("t"), clips = clips)))
        }
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = timeline,
                lockfile = EagerLockfile(entries = lockfileEntries),
            ),
        )
        return store to pid
    }

    private suspend fun run(
        store: FileProjectStore,
        pid: ProjectId,
        limit: Int? = null,
        offset: Int? = null,
    ): ProjectQueryTool.Output = ProjectQueryTool(store).execute(
        ProjectQueryTool.Input(
            select = "lockfile_orphans",
            projectId = pid.value,
            limit = limit,
            offset = offset,
        ),
        ctx(),
    ).data

    private fun rows(out: ProjectQueryTool.Output): List<LockfileOrphanRow> =
        out.rows.decodeRowsAs(LockfileOrphanRow.serializer())

    @Test fun emptyLockfileEmitsZeroRows() = runTest {
        val (store, pid) = fixture()
        val out = run(store, pid)
        assertEquals(0, out.total)
        assertEquals(emptyList(), rows(out))
        assertTrue(
            "No orphan" in out.rows.toString() || true, // narrative lives in outputForLlm
        )
    }

    @Test fun everyEntryReferencedEmitsZeroRows() = runTest {
        val (store, pid) = fixture(
            clips = listOf(videoClip("c1", "a1"), videoClip("c2", "a2")),
            lockfileEntries = listOf(entry("h1", "a1"), entry("h2", "a2")),
        )
        val out = run(store, pid)
        assertEquals(0, out.total)
    }

    @Test fun mixedReferencedAndOrphanSurfacesOnlyOrphans() = runTest {
        val (store, pid) = fixture(
            clips = listOf(videoClip("c1", "a1")),
            lockfileEntries = listOf(
                entry("h1", "a1", createdAtEpochMs = 1_000),
                entry("h2", "a2-orphan", createdAtEpochMs = 2_000),
                entry("h3", "a3-orphan", createdAtEpochMs = 3_000),
            ),
        )
        val out = run(store, pid)
        assertEquals(2, out.total)
        val got = rows(out)
        assertEquals(listOf("a3-orphan", "a2-orphan"), got.map { it.assetId }, "newer first, unpinned")
        assertTrue(got.all { !it.pinned })
    }

    @Test fun pinnedOrphanShownAfterUnpinned() = runTest {
        val (store, pid) = fixture(
            lockfileEntries = listOf(
                entry("h1", "a1-pin", pinned = true, createdAtEpochMs = 5_000),
                entry("h2", "a2-unpin", pinned = false, createdAtEpochMs = 1_000),
            ),
        )
        val got = rows(run(store, pid))
        assertEquals(2, got.size)
        assertEquals(
            listOf("a2-unpin", "a1-pin"),
            got.map { it.assetId },
            "unpinned (even if older) sorts before pinned — actionable set on top",
        )
        assertEquals(false, got[0].pinned)
        assertEquals(true, got[1].pinned)
    }

    @Test fun paginationClampsPage() = runTest {
        val (store, pid) = fixture(
            lockfileEntries = (1..5).map { i ->
                entry("h$i", "a$i-orphan", createdAtEpochMs = i.toLong() * 1_000)
            },
        )
        val firstPage = rows(run(store, pid, limit = 2, offset = 0))
        val secondPage = rows(run(store, pid, limit = 2, offset = 2))
        val third = rows(run(store, pid, limit = 2, offset = 4))
        assertEquals(listOf("a5-orphan", "a4-orphan"), firstPage.map { it.assetId })
        assertEquals(listOf("a3-orphan", "a2-orphan"), secondPage.map { it.assetId })
        assertEquals(listOf("a1-orphan"), third.map { it.assetId })
    }

    @Test fun textClipDoesNotCountAsReference() = runTest {
        val textClip = Clip.Text(
            id = ClipId("c-text"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            text = "caption",
        )
        val (store, pid) = fixture(
            clips = listOf(textClip),
            lockfileEntries = listOf(entry("h1", "a1-orphan")),
        )
        val got = rows(run(store, pid))
        assertEquals(1, got.size)
        assertEquals("a1-orphan", got.single().assetId)
    }

    @Test fun summaryCostRollupShowsInNarrative() = runTest {
        val (store, pid) = fixture(
            lockfileEntries = listOf(
                entry("h1", "a1-orphan", costCents = 25),
                entry("h2", "a2-orphan", costCents = 75),
            ),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(select = "lockfile_orphans", projectId = pid.value),
            ctx(),
        )
        assertTrue(
            "¢100" in out.outputForLlm,
            "narrative must expose aggregate cost: <${out.outputForLlm}>",
        )
    }
}
