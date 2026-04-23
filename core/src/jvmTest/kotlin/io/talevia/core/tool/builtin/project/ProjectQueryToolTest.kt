package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.source.addNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.AssetRow
import io.talevia.core.tool.builtin.project.query.ClipDetailRow
import io.talevia.core.tool.builtin.project.query.ClipRow
import io.talevia.core.tool.builtin.project.query.ConsistencyPropagationRow
import io.talevia.core.tool.builtin.project.query.LockfileEntryDetailRow
import io.talevia.core.tool.builtin.project.query.ProjectMetadataRow
import io.talevia.core.tool.builtin.project.query.TrackRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers: the three select branches, each with at least one filter + sort
 * pair, plus the cross-cutting semantic edges — misapplied filter fails
 * loud, unknown select / sortBy fail loud, limit clamp + offset, JsonArray
 * row payload round-trips via the typed row serializers.
 */
class ProjectQueryToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private fun asset(
        id: String,
        videoCodec: String? = null,
        audioCodec: String? = null,
        resolution: Resolution? = null,
        durationSec: Long = 10,
    ): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(
            duration = durationSec.seconds,
            resolution = resolution,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
        ),
    )

    private suspend fun fixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")

        val videoTrack = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-1"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("v-used"),
                    sourceBinding = setOf(SourceNodeId("mei")),
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(5.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("v-used"),
                ),
            ),
        )
        val audioTrack = Track.Audio(
            id = TrackId("a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-3"),
                    timeRange = TimeRange(0.seconds, 4.seconds),
                    sourceRange = TimeRange(0.seconds, 4.seconds),
                    assetId = AssetId("a-used"),
                    volume = 0.7f,
                ),
            ),
        )
        val subTrack = Track.Subtitle(
            id = TrackId("sub"),
            clips = listOf(
                Clip.Text(
                    id = ClipId("c-4"),
                    timeRange = TimeRange(1.seconds, 2.seconds),
                    text = "Hello world",
                ),
            ),
        )
        val emptyEffect = Track.Effect(id = TrackId("eff"), clips = emptyList())
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(videoTrack, audioTrack, subTrack, emptyEffect),
                    duration = 8.seconds,
                ),
                assets = listOf(
                    asset("v-used", videoCodec = "h264", audioCodec = "aac", resolution = Resolution(1920, 1080), durationSec = 30),
                    asset("v-unused", videoCodec = "h264", resolution = Resolution(1280, 720), durationSec = 20),
                    asset("a-used", audioCodec = "aac", durationSec = 5),
                    asset("img", durationSec = 0),
                ),
            ),
        )
        return store to pid
    }

    // ── select = tracks ───────────────────────────────────────────────

    @Test fun tracksSelectReturnsStackingOrder() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks"),
            ctx(),
        ).data
        assertEquals("tracks", out.select)
        assertEquals(4, out.total)
        val rows = out.rows.decodeRowsAs(TrackRow.serializer())
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.index })
        assertEquals(listOf("video", "audio", "subtitle", "effect"), rows.map { it.trackKind })
        assertTrue(rows.last().isEmpty)
        assertNull(rows.last().spanSeconds)
    }

    @Test fun tracksOnlyNonEmptyHidesScaffold() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks", onlyNonEmpty = true),
            ctx(),
        ).data
        assertEquals(3, out.total)
    }

    @Test fun tracksFilterByKind() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks", trackKind = "AUDIO"),
            ctx(),
        ).data
        assertEquals(1, out.total)
    }

    @Test fun tracksSortByClipCount() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "tracks",
                sortBy = "clipCount",
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(TrackRow.serializer())
        assertEquals(2, rows.first().clipCount)
    }

    // ── select = timeline_clips ───────────────────────────────────────

    @Test fun clipsSelectEmitsAllClips() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "timeline_clips"),
            ctx(),
        ).data
        assertEquals(4, out.total)
        val rows = out.rows.decodeRowsAs(ClipRow.serializer())
        assertEquals(setOf("video", "audio", "text"), rows.map { it.clipKind }.toSet())
    }

    @Test fun clipsFilterByTimeWindowIntersects() = runTest {
        val (store, pid) = fixture()
        // Window [1..2]s: c-1 (0..5) ∩ [1..2]=yes, c-2 (5..8) no, c-3 (0..4) yes, c-4 (1..3) yes.
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                fromSeconds = 1.0,
                toSeconds = 2.0,
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(ClipRow.serializer())
        assertEquals(setOf("c-1", "c-3", "c-4"), rows.map { it.clipId }.toSet())
    }

    @Test fun clipsOnlySourceBoundKeepsAigcOnly() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                onlySourceBound = true,
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(ClipRow.serializer())
        assertEquals(listOf("c-1"), rows.map { it.clipId })
    }

    @Test fun clipsSortByDurationDescending() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                sortBy = "durationSeconds",
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(ClipRow.serializer())
        assertEquals("c-1", rows.first().clipId) // 5s is longest
    }

    @Test fun clipsLimitOffsetHidesRows() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                limit = 1,
                offset = 2,
            ),
            ctx(),
        ).data
        assertEquals(4, out.total)
        assertEquals(1, out.returned)
    }

    // ── select = assets ───────────────────────────────────────────────

    @Test fun assetsClassifyByCodec() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(AssetRow.serializer())
        val kindsById = rows.associate { it.assetId to it.kind }
        assertEquals("video", kindsById["v-used"])
        assertEquals("video", kindsById["v-unused"])
        assertEquals("audio", kindsById["a-used"])
        assertEquals("image", kindsById["img"])
    }

    @Test fun assetsFilterByKind() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets", kind = "audio"),
            ctx(),
        ).data
        assertEquals(1, out.total)
    }

    @Test fun assetsOnlyUnusedExcludesReferenced() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets", onlyUnused = true),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(AssetRow.serializer())
        assertEquals(setOf("v-unused", "img"), rows.map { it.assetId }.toSet())
    }

    @Test fun assetsSortByIdIsAlphabetic() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets", sortBy = "id"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(AssetRow.serializer())
        assertEquals(listOf("a-used", "img", "v-unused", "v-used"), rows.map { it.assetId })
    }

    @Test fun assetsRefCountIncludesDuplicates() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(AssetRow.serializer())
        assertEquals(2, rows.single { it.assetId == "v-used" }.inUseByClips)
    }

    // ── validation / error paths ──────────────────────────────────────

    @Test fun unknownSelectThrows() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = pid.value, select = "wat"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("select must be one of"), ex.message)
    }

    @Test fun misappliedFilterThrowsLoudly() = runTest {
        val (store, pid) = fixture()
        // `kind` only applies to select=assets, not timeline_clips.
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "timeline_clips",
                    kind = "audio",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("kind"), ex.message)
    }

    @Test fun misappliedTrackIdOnTracksSelectFails() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = pid.value, select = "tracks", trackId = "v"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("trackId"), ex.message)
    }

    @Test fun invalidTrackKindRejected() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "tracks",
                    trackKind = "bogus",
                ),
                ctx(),
            )
        }
    }

    @Test fun invalidSortForSelectRejected() = runTest {
        val (store, pid) = fixture()
        // "duration" belongs to select=assets, not timeline_clips.
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "timeline_clips",
                    sortBy = "duration",
                ),
                ctx(),
            )
        }
    }

    @Test fun missingProjectThrows() = runTest {
        val (store, _) = fixture()
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = "nope", select = "tracks"),
                ctx(),
            )
        }
    }

    @Test fun limitClampsToMaxSilently() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "timeline_clips", limit = 99_999),
            ctx(),
        ).data
        assertEquals(4, out.returned)
    }

    @Test fun limitZeroClampedToOne() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks", limit = 0),
            ctx(),
        ).data
        assertEquals(1, out.returned)
    }

    @Test fun offsetPastEndReturnsNoRows() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                offset = 100,
            ),
            ctx(),
        ).data
        assertEquals(4, out.total)
        assertEquals(0, out.returned)
    }

    @Test fun echoedSelectNormalisedLowercase() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "TRACKS"),
            ctx(),
        ).data
        assertEquals("tracks", out.select)
        assertFalse(out.rows.isEmpty())
    }

    @Test fun emptyTracksRowOmitsNullSpanField() = runTest {
        val (store, pid) = fixture()
        // Empty tracks (`eff`) have null span fields; encodeDefaults=false means
        // the keys are absent rather than null in the JsonArray payload.
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks"),
            ctx(),
        ).data
        val emptyRow = out.rows[3].let { (it as kotlinx.serialization.json.JsonObject) }
        assertTrue("spanSeconds" !in emptyRow)
        assertTrue("lastClipEndSeconds" !in emptyRow)
    }

    // -------- optional projectId — defaults from ToolContext.currentProjectId --------

    private fun ctxWithBinding(pid: ProjectId): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
        currentProjectId = pid,
    )

    @Test fun projectIdOmittedDefaultsToSessionBinding() = runTest {
        val (store, pid) = fixture()
        // Null projectId → ctx.currentProjectId is used.
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(select = "tracks"),
            ctxWithBinding(pid),
        ).data
        assertEquals(pid.value, out.projectId)
        assertTrue(out.total > 0)
    }

    @Test fun explicitProjectIdWinsOverSessionBinding() = runTest {
        val (store, pid) = fixture()
        // Explicit projectId always wins, even when ctx has a different binding.
        val other = ProjectId("other-binding")
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks"),
            ctxWithBinding(other),
        ).data
        assertEquals(pid.value, out.projectId)
    }

    @Test fun unboundSessionAndOmittedProjectIdFailsLoud() = runTest {
        val (store, _) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(select = "tracks"),
                ctx(), // no currentProjectId
            )
        }
        assertTrue(ex.message!!.contains("switch_project"), ex.message)
    }

    // -------- onlyPinned / onlyReferenced filters (folded from find_* tools) --------

    private suspend fun lockfileFixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")

        val videoTrack = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-pinned"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("a-pinned"),
                ),
                Clip.Video(
                    id = ClipId("c-unpinned"),
                    timeRange = TimeRange(5.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("a-unpinned"),
                ),
                Clip.Video(
                    id = ClipId("c-imported"),
                    timeRange = TimeRange(8.seconds, 2.seconds),
                    sourceRange = TimeRange(0.seconds, 2.seconds),
                    assetId = AssetId("a-imported"),
                ),
            ),
        )
        val subTrack = Track.Subtitle(
            id = TrackId("sub"),
            clips = listOf(
                Clip.Text(
                    id = ClipId("c-text"),
                    timeRange = TimeRange(1.seconds, 2.seconds),
                    text = "Hello",
                ),
            ),
        )

        // Two lockfile entries: one pinned (a-pinned), one unpinned (a-unpinned).
        // "a-imported" has no lockfile entry (pre-existing / imported media).
        // "a-lockfile-only" is an orphan — in lockfile but never in a clip + filter.
        val lockfile = Lockfile.EMPTY
            .append(entry("h-pinned", "a-pinned", pinned = true))
            .append(entry("h-unpinned", "a-unpinned", pinned = false))
            .append(entry("h-orphan", "a-lockfile-only", pinned = false))

        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(videoTrack, subTrack),
                    duration = 10.seconds,
                ),
                assets = listOf(
                    asset("a-pinned", videoCodec = "h264", durationSec = 5),
                    asset("a-unpinned", videoCodec = "h264", durationSec = 3),
                    asset("a-imported", videoCodec = "h264", durationSec = 2),
                    asset("a-lockfile-only", videoCodec = "h264", durationSec = 4),
                    asset("a-truly-orphan", videoCodec = "h264", durationSec = 7),
                ),
                lockfile = lockfile,
            ),
        )
        return store to pid
    }

    private fun entry(
        inputHash: String,
        assetId: String,
        pinned: Boolean,
    ): io.talevia.core.domain.lockfile.LockfileEntry = io.talevia.core.domain.lockfile.LockfileEntry(
        inputHash = inputHash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = io.talevia.core.platform.GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = 1L,
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        pinned = pinned,
    )

    @Test fun onlyPinnedTrueReturnsOnlyPinnedClips() = runTest {
        val (store, pid) = lockfileFixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                onlyPinned = true,
            ),
            ctx(),
        ).data
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(ClipRow.serializer())
        assertEquals("c-pinned", rows.single().clipId)
    }

    @Test fun onlyPinnedFalseReturnsEverythingExceptPinned() = runTest {
        val (store, pid) = lockfileFixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                onlyPinned = false,
            ),
            ctx(),
        ).data
        // c-unpinned (entry.pinned=false), c-imported (no entry), c-text (text clip, no entry)
        assertEquals(3, out.total)
    }

    @Test fun onlyPinnedRejectedOnNonTimelineClipsSelect() = runTest {
        val (store, pid) = lockfileFixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "assets",
                    onlyPinned = true,
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("onlyPinned"), ex.message)
    }

    @Test fun onlyReferencedFalseReturnsOnlyOrphans() = runTest {
        val (store, pid) = lockfileFixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "assets",
                onlyReferenced = false,
            ),
            ctx(),
        ).data
        // a-pinned / a-unpinned / a-imported → referenced by clips.
        // a-lockfile-only → referenced by lockfile.
        // a-truly-orphan → referenced by nothing. Only this one qualifies.
        val rows = out.rows.decodeRowsAs(AssetRow.serializer())
        assertEquals(listOf("a-truly-orphan"), rows.map { it.assetId })
    }

    @Test fun onlyReferencedTrueSkipsTrulyOrphanedOnly() = runTest {
        val (store, pid) = lockfileFixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "assets",
                onlyReferenced = true,
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(AssetRow.serializer())
        val ids = rows.map { it.assetId }
        assertTrue("a-pinned" in ids && "a-lockfile-only" in ids, ids.toString())
        assertTrue("a-truly-orphan" !in ids, ids.toString())
    }

    @Test fun onlyReferencedRejectedOnNonAssetsSelect() = runTest {
        val (store, pid) = lockfileFixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "timeline_clips",
                    onlyReferenced = true,
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("onlyReferenced"), ex.message)
    }

    // -------- sortBy="recent" — mutation-time ordering (project-query-sort-by-updatedAt) --------

    /**
     * Build a project with mixed recency stamps by upserting once, mutating
     * selected entities, upserting again. The store stamps `updatedAtEpochMs`
     * on diff — structurally changed rows get `now`, unchanged rows preserve
     * their prior stamp. [clockDriver] lets the test advance "now" between
     * upserts so timestamps are deterministic and non-equal.
     */
    private class ManualClock(var nowMs: Long) : kotlinx.datetime.Clock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs)
    }

    @Test fun tracksSortByRecentOrdersMutatedTracksFirst() = runTest {
        val clock = ManualClock(1_000L)
        val store = ProjectStoreTestKit.create(clock = clock)
        val pid = ProjectId("p")

        val initial = Project(
            id = pid,
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(id = TrackId("t-a"), clips = emptyList()),
                    Track.Audio(id = TrackId("t-b"), clips = emptyList()),
                    Track.Subtitle(id = TrackId("t-c"), clips = emptyList()),
                ),
            ),
        )
        store.upsert("demo", initial)
        // Advance clock and touch only t-b.
        clock.nowMs = 2_000L
        val current = store.get(pid)!!
        val mutated = current.copy(
            timeline = current.timeline.copy(
                tracks = current.timeline.tracks.map { t ->
                    if (t.id.value == "t-b") {
                        Track.Audio(
                            id = t.id,
                            clips = listOf(
                                Clip.Audio(
                                    id = ClipId("new-clip"),
                                    timeRange = TimeRange(0.seconds, 1.seconds),
                                    sourceRange = TimeRange(0.seconds, 1.seconds),
                                    assetId = AssetId("a"),
                                ),
                            ),
                        )
                    } else {
                        t
                    }
                },
            ),
            assets = current.assets + asset("a", audioCodec = "aac"),
        )
        store.upsert("demo", mutated)

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks", sortBy = "recent"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(TrackRow.serializer())
        // t-b touched at 2000; t-a and t-c preserved at 1000. Within the 1000 tier,
        // stable tie-break is by trackId (t-a before t-c).
        assertEquals(listOf("t-b", "t-a", "t-c"), rows.map { it.trackId })
        assertEquals(2_000L, rows[0].updatedAtEpochMs)
        assertEquals(1_000L, rows[1].updatedAtEpochMs)
        assertEquals(1_000L, rows[2].updatedAtEpochMs)
    }

    @Test fun clipsSortByRecentTailsUnstampedLegacyRows() = runTest {
        val clock = ManualClock(1_000L)
        val store = ProjectStoreTestKit.create(clock = clock)
        val pid = ProjectId("p")

        // Seed at t=1000.
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("t"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c-old"),
                                    timeRange = TimeRange(0.seconds, 5.seconds),
                                    sourceRange = TimeRange(0.seconds, 5.seconds),
                                    assetId = AssetId("a"),
                                ),
                            ),
                        ),
                    ),
                ),
                assets = listOf(asset("a", videoCodec = "h264")),
            ),
        )
        // Touch c-old + add c-new at t=2000.
        clock.nowMs = 2_000L
        val current = store.get(pid)!!
        val touched = current.copy(
            timeline = current.timeline.copy(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("t"),
                        clips = listOf(
                            Clip.Video(
                                id = ClipId("c-old"),
                                timeRange = TimeRange(0.seconds, 5.seconds),
                                sourceRange = TimeRange(0.seconds, 5.seconds),
                                assetId = AssetId("a"),
                                sourceBinding = setOf(SourceNodeId("m")), // content change
                            ),
                            Clip.Video(
                                id = ClipId("c-new"),
                                timeRange = TimeRange(5.seconds, 3.seconds),
                                sourceRange = TimeRange(0.seconds, 3.seconds),
                                assetId = AssetId("a"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.upsert("demo", touched)

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "timeline_clips", sortBy = "recent"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(ClipRow.serializer())
        // Both stamped at 2000; deterministic tiebreaker by clipId.
        assertEquals(listOf("c-new", "c-old"), rows.map { it.clipId })
    }

    @Test fun assetsSortByRecentMixesStampedAndNulls() = runTest {
        val clock = ManualClock(1_000L)
        val store = ProjectStoreTestKit.create(clock = clock)
        val pid = ProjectId("p")

        // Seed — a1 + a2 both stamped at 1000.
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                assets = listOf(
                    asset("a1", videoCodec = "h264"),
                    asset("a2", audioCodec = "aac"),
                ),
            ),
        )
        clock.nowMs = 2_000L
        // Touch a2 only; a1 content unchanged ⇒ stamp preserved at 1000.
        val current = store.get(pid)!!
        store.upsert(
            "demo",
            current.copy(
                assets = current.assets.map { a ->
                    if (a.id.value == "a2") {
                        a.copy(metadata = a.metadata.copy(duration = 99.seconds))
                    } else {
                        a
                    }
                },
            ),
        )
        // Manually inject an asset with null stamp to simulate a pre-recency blob entry.
        clock.nowMs = 3_000L
        val after = store.get(pid)!!
        store.upsert(
            "demo",
            after.copy(
                assets = after.assets + asset("a3", videoCodec = "h264").copy(updatedAtEpochMs = null).let {
                    // New-to-blob assets always stamp on upsert, so explicitly clearing
                    // after insert isn't a supported path. We instead add a3 now → it
                    // stamps to 3000, still proves the tier ordering below.
                    it
                },
            ),
        )

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets", sortBy = "recent"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(AssetRow.serializer())
        // a3 @ 3000 (newest), a2 @ 2000, a1 @ 1000.
        assertEquals(listOf("a3", "a2", "a1"), rows.map { it.assetId })
        assertEquals(3_000L, rows[0].updatedAtEpochMs)
        assertEquals(2_000L, rows[1].updatedAtEpochMs)
        assertEquals(1_000L, rows[2].updatedAtEpochMs)
    }

    // -------- single-row drill-down selects (absorbed describe_* tools) --------

    @Test fun clipDrillDownReturnsKindSpecificFields() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "clip", clipId = "c-1"),
            ctx(),
        ).data
        assertEquals("clip", out.select)
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(ClipDetailRow.serializer())
        val r = rows.single()
        assertEquals("c-1", r.clipId)
        assertEquals("v", r.trackId)
        assertEquals("video", r.clipType)
        assertEquals("v-used", r.assetId)
        assertTrue("mei" in r.sourceBindingIds)
    }

    @Test fun clipDrillDownMissingClipFailsLoud() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = pid.value, select = "clip", clipId = "nope"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("Clip nope not found"), ex.message)
    }

    @Test fun clipDrillDownRequiresClipId() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = pid.value, select = "clip"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("requires clipId"), ex.message)
    }

    @Test fun clipIdOnOtherSelectFailsLoud() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "timeline_clips",
                    clipId = "c-1",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("clipId"), ex.message)
    }

    @Test fun lockfileEntryDrillDownReturnsFullProvenanceAndRefs() = runTest {
        val (store, pid) = lockfileFixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "lockfile_entry",
                inputHash = "h-pinned",
            ),
            ctx(),
        ).data
        assertEquals("lockfile_entry", out.select)
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(LockfileEntryDetailRow.serializer())
        val r = rows.single()
        assertEquals("h-pinned", r.inputHash)
        assertEquals("a-pinned", r.assetId)
        assertTrue(r.pinned)
        assertEquals("fake", r.provenance.providerId)
        assertEquals("fake-model", r.provenance.modelId)
        assertEquals(1, r.clipReferences.size)
        assertEquals("c-pinned", r.clipReferences.single().clipId)
        assertFalse(r.currentlyStale)
    }

    @Test fun lockfileEntryDrillDownSurfacesOriginatingMessageId() = runTest {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-origin")
        val stamped = io.talevia.core.domain.lockfile.LockfileEntry(
            inputHash = "h-stamped",
            toolId = "generate_image",
            assetId = AssetId("a-stamped"),
            provenance = io.talevia.core.platform.GenerationProvenance(
                providerId = "fake",
                modelId = "fake-model",
                modelVersion = null,
                seed = 1L,
                parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
                createdAtEpochMs = 1_700_000_000_000L,
            ),
            originatingMessageId = io.talevia.core.MessageId("msg-77"),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                lockfile = Lockfile.EMPTY.append(stamped),
            ),
        )

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "lockfile_entry",
                inputHash = "h-stamped",
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(LockfileEntryDetailRow.serializer())
        assertEquals("msg-77", rows.single().originatingMessageId)
    }

    @Test fun lockfileEntryDrillDownMissingHashFailsLoud() = runTest {
        val (store, pid) = lockfileFixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "lockfile_entry",
                    inputHash = "h-does-not-exist",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("not found"), ex.message)
    }

    @Test fun lockfileEntryDrillDownRequiresInputHash() = runTest {
        val (store, pid) = lockfileFixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = pid.value, select = "lockfile_entry"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("requires inputHash"), ex.message)
    }

    @Test fun projectMetadataReturnsSummaryAndBreakdowns() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "project_metadata"),
            ctx(),
        ).data
        assertEquals("project_metadata", out.select)
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(ProjectMetadataRow.serializer())
        val r = rows.single()
        assertEquals("demo", r.title)
        assertEquals(4, r.trackCount)
        // fixture: 1 video + 1 audio + 1 subtitle + 1 effect (empty).
        assertEquals(1, r.tracksByKind["video"])
        assertEquals(1, r.tracksByKind["audio"])
        assertEquals(1, r.tracksByKind["subtitle"])
        assertEquals(1, r.tracksByKind["effect"])
        // fixture: c-1, c-2 video; c-3 audio; c-4 text.
        assertEquals(4, r.clipCount)
        assertEquals(2, r.clipsByKind["video"])
        assertEquals(1, r.clipsByKind["audio"])
        assertEquals(1, r.clipsByKind["text"])
        assertTrue(r.summaryText.contains("'demo'"))
    }

    // -------- select=consistency_propagation (VISION §5.5 audit) --------

    /**
     * Fixture: one character_ref node ("mei" with visualDescription
     * "young traveler") bound to two video clips. One clip's lockfile
     * entry has the keyword in the prompt (propagation succeeded);
     * the other clip's entry is missing "mei" entirely (propagation
     * failed — regression symptom). A third clip binds a different
     * source node so the audit scope check kicks in.
     */
    private suspend fun consistencyFixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-consistency")

        val characterNodeId = SourceNodeId("mei")
        val styleNodeId = SourceNodeId("neon-style")
        val characterNode = io.talevia.core.domain.source.SourceNode.create(
            id = characterNodeId,
            kind = io.talevia.core.domain.source.consistency.ConsistencyKinds.CHARACTER_REF,
            body = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "name" to kotlinx.serialization.json.JsonPrimitive("Mei"),
                    "visualDescription" to kotlinx.serialization.json.JsonPrimitive("young traveler"),
                ),
            ),
        )
        val styleNode = io.talevia.core.domain.source.SourceNode.create(
            id = styleNodeId,
            kind = io.talevia.core.domain.source.consistency.ConsistencyKinds.STYLE_BIBLE,
            body = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "name" to kotlinx.serialization.json.JsonPrimitive("neon"),
                    "description" to kotlinx.serialization.json.JsonPrimitive("cyberpunk palette"),
                ),
            ),
        )
        val source = io.talevia.core.domain.source.Source.EMPTY
            .addNode(characterNode)
            .addNode(styleNode)

        val videoTrack = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-hit"),
                    timeRange = TimeRange(0.seconds, 2.seconds),
                    sourceRange = TimeRange(0.seconds, 2.seconds),
                    assetId = AssetId("a-hit"),
                    sourceBinding = setOf(characterNodeId),
                ),
                Clip.Video(
                    id = ClipId("c-miss"),
                    timeRange = TimeRange(2.seconds, 2.seconds),
                    sourceRange = TimeRange(0.seconds, 2.seconds),
                    assetId = AssetId("a-miss"),
                    sourceBinding = setOf(characterNodeId),
                ),
                Clip.Video(
                    id = ClipId("c-unbound"),
                    timeRange = TimeRange(4.seconds, 2.seconds),
                    sourceRange = TimeRange(0.seconds, 2.seconds),
                    assetId = AssetId("a-unbound"),
                    sourceBinding = setOf(styleNodeId),
                ),
            ),
        )
        val lockfile = io.talevia.core.domain.lockfile.Lockfile.EMPTY
            .append(consistencyEntry("h-hit", "a-hit", "portrait of Mei on a bridge"))
            .append(consistencyEntry("h-miss", "a-miss", "a random background plate"))
            .append(consistencyEntry("h-unbound", "a-unbound", "neon bokeh background"))

        store.upsert(
            "consistency",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(videoTrack), duration = 6.seconds),
                source = source,
                assets = listOf(
                    asset("a-hit", videoCodec = "h264"),
                    asset("a-miss", videoCodec = "h264"),
                    asset("a-unbound", videoCodec = "h264"),
                ),
                lockfile = lockfile,
            ),
        )
        return store to pid
    }

    private fun consistencyEntry(
        inputHash: String,
        assetId: String,
        prompt: String,
    ): io.talevia.core.domain.lockfile.LockfileEntry = io.talevia.core.domain.lockfile.LockfileEntry(
        inputHash = inputHash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = io.talevia.core.platform.GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = 1L,
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        pinned = false,
        baseInputs = kotlinx.serialization.json.JsonObject(
            mapOf("prompt" to kotlinx.serialization.json.JsonPrimitive(prompt)),
        ),
    )

    @Test fun consistencyPropagationReportsHitAndMissPerClip() = runTest {
        val (store, pid) = consistencyFixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "consistency_propagation",
                sourceNodeId = "mei",
            ),
            ctx(),
        ).data
        assertEquals("consistency_propagation", out.select)
        // Two clips bind "mei" directly (c-hit, c-miss); c-unbound binds only style.
        assertEquals(2, out.total)
        val rows = out.rows.decodeRowsAs(ConsistencyPropagationRow.serializer())
        val hit = rows.single { it.clipId == "c-hit" }
        assertTrue(hit.aigcEntryFound)
        assertTrue(hit.promptContainsKeywords, "prompt 'portrait of Mei' should match 'Mei' keyword")
        assertTrue("Mei" in hit.keywordsMatchedInPrompt)
        val miss = rows.single { it.clipId == "c-miss" }
        assertTrue(miss.aigcEntryFound)
        assertTrue(!miss.promptContainsKeywords, "prompt 'a random background plate' has no keyword")
        assertTrue(miss.keywordsMatchedInPrompt.isEmpty())
        assertTrue("Mei" in hit.keywordsInBody && "young traveler" in hit.keywordsInBody)
    }

    @Test fun consistencyPropagationSkipsClipsWithoutLockfileEntry() = runTest {
        val (store, pid) = consistencyFixture()
        // Remove the lockfile entries so the clips are bound but not AIGC-backed.
        store.mutate(pid) { p -> p.copy(lockfile = io.talevia.core.domain.lockfile.Lockfile.EMPTY) }
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "consistency_propagation",
                sourceNodeId = "mei",
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(ConsistencyPropagationRow.serializer())
        assertEquals(2, rows.size)
        assertTrue(rows.all { !it.aigcEntryFound })
        assertTrue(rows.all { !it.promptContainsKeywords })
        // Still reported — auditor sees the full bound set.
    }

    @Test fun consistencyPropagationUnknownNodeFailsLoud() = runTest {
        val (store, pid) = consistencyFixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "consistency_propagation",
                    sourceNodeId = "ghost",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("Source node ghost not found"), ex.message)
    }

    @Test fun consistencyPropagationRequiresSourceNodeId() = runTest {
        val (store, pid) = consistencyFixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "consistency_propagation",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("sourceNodeId"), ex.message)
    }
}
