package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runProjectMetadataQuery] —
 * `project_query(select=project_metadata)`. The single-row
 * drill-down replacing the deleted `describe_project` tool;
 * pre-renders a ~300-char `summaryText` the LLM can quote
 * verbatim. Cycle 139 audit: 260 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Track + clip kind buckets are stable.** All four track
 *    kinds (`video`, `audio`, `subtitle`, `effect`) and three
 *    clip kinds (`video`, `audio`, `text`) appear in the row
 *    even when zero — informative zeros, never elided. A
 *    regression that drops empty buckets would let the LLM
 *    confuse "no audio track exists" with "audio track key
 *    missing because forgotten." Pinned by an empty-timeline
 *    case asserting all keys present at zero.
 *
 * 2. **Default 1080p profile elides `outputProfile`; non-default
 *    populates it.** Per kdoc-implicit compaction: the row's
 *    `outputProfile` is null when `project.outputProfile ==
 *    DEFAULT_1080P` (don't burn LLM tokens on the default),
 *    populated otherwise. Pin both branches — drift to
 *    "always populate" silently doubles the row size on every
 *    project_metadata call.
 *
 * 3. **Recent snapshots cap at [METADATA_MAX_RECENT_SNAPSHOTS]
 *    (=5), sorted DESC by `capturedAtEpochMs`.** A 6-snapshot
 *    project with timestamps 1..6 surfaces snapshots 6/5/4/3/2
 *    in row.recentSnapshots — newest first, oldest dropped.
 *    Drift to insertion-order or ASC sort would surface stale
 *    snapshots while the new ones get hidden.
 */
class ProjectMetadataQueryTest {

    private val timeRange = TimeRange(start = kotlin.time.Duration.ZERO, duration = 1.seconds)

    /** Minimal ProjectStore — only `summary(id)` is exercised. */
    private class FakeProjectStore(
        private val title: String = "Test Project",
        private val createdAtEpochMs: Long = 1_000L,
        private val updatedAtEpochMs: Long = 2_000L,
        private val returnNullSummary: Boolean = false,
    ) : ProjectStore {
        override suspend fun get(id: ProjectId): Project? = null
        override suspend fun upsert(title: String, project: Project) = error("not used")
        override suspend fun list(): List<Project> = emptyList()
        override suspend fun delete(id: ProjectId, deleteFiles: Boolean) = error("not used")
        override suspend fun setTitle(id: ProjectId, title: String) = error("not used")
        override suspend fun summary(id: ProjectId): ProjectSummary? =
            if (returnNullSummary) null
            else ProjectSummary(
                id = id.value,
                title = title,
                createdAtEpochMs = createdAtEpochMs,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        override suspend fun listSummaries(): List<ProjectSummary> = emptyList()
        override suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project =
            error("not used")
    }

    private fun project(
        id: String = "p",
        timeline: Timeline = Timeline(),
        outputProfile: OutputProfile = OutputProfile.DEFAULT_1080P,
        assets: List<MediaAsset> = emptyList(),
        source: Source = Source.EMPTY,
        lockfile: EagerLockfile = EagerLockfile(),
        snapshots: List<ProjectSnapshot> = emptyList(),
    ): Project = Project(
        id = ProjectId(id),
        timeline = timeline,
        assets = assets,
        source = source,
        outputProfile = outputProfile,
        lockfile = lockfile,
        snapshots = snapshots,
    )

    private fun runQuery(
        project: Project,
        store: ProjectStore = FakeProjectStore(),
    ) = runBlocking {
        runProjectMetadataQuery(project, store, ProjectQueryTool.Input(select = "project_metadata"))
    }

    private fun decodeRow(out: ProjectQueryTool.Output): ProjectMetadataRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectMetadataRow.serializer()),
            out.rows,
        ).single()

    private fun lockfileEntry(input: String, tool: String, asset: String) = LockfileEntry(
        inputHash = input,
        toolId = tool,
        assetId = AssetId(asset),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        sourceBinding = emptySet(),
    )

    // ── kind-bucket stability (informative zeros) ───────────────

    @Test fun emptyTimelineExposesAllFourTrackKindsAndThreeClipKindsAsZero() {
        // Pin: regression dropping empty buckets would let the
        // LLM confuse "no audio" with "key missing". Asserts
        // every keyspace present and zeroed.
        val row = decodeRow(runQuery(project()).data)
        assertEquals(0, row.trackCount)
        assertEquals(0, row.clipCount)
        assertEquals(
            mapOf("video" to 0, "audio" to 0, "subtitle" to 0, "effect" to 0),
            row.tracksByKind,
            "all 4 track kinds present even at zero",
        )
        assertEquals(
            mapOf("video" to 0, "audio" to 0, "text" to 0),
            row.clipsByKind,
            "all 3 clip kinds present even at zero",
        )
    }

    @Test fun multiKindTimelineCountsByKindCorrectly() {
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    TrackId("vt1"),
                    listOf(
                        Clip.Video(
                            id = ClipId("v1"),
                            timeRange = timeRange,
                            sourceRange = timeRange,
                            assetId = AssetId("a-v1"),
                        ),
                        Clip.Video(
                            id = ClipId("v2"),
                            timeRange = TimeRange(1.seconds, 1.seconds),
                            sourceRange = timeRange,
                            assetId = AssetId("a-v2"),
                        ),
                    ),
                ),
                Track.Audio(
                    TrackId("at1"),
                    listOf(
                        Clip.Audio(
                            id = ClipId("a1"),
                            timeRange = timeRange,
                            sourceRange = timeRange,
                            assetId = AssetId("a-a1"),
                        ),
                    ),
                ),
                Track.Subtitle(
                    TrackId("st1"),
                    listOf(
                        Clip.Text(
                            id = ClipId("t1"),
                            timeRange = timeRange,
                            text = "hello",
                            style = TextStyle(),
                        ),
                    ),
                ),
                Track.Effect(TrackId("et1")),
            ),
        )
        val row = decodeRow(runQuery(project(timeline = timeline)).data)
        assertEquals(4, row.trackCount)
        assertEquals(
            mapOf("video" to 1, "audio" to 1, "subtitle" to 1, "effect" to 1),
            row.tracksByKind,
        )
        assertEquals(4, row.clipCount, "2 video + 1 audio + 1 text")
        assertEquals(
            mapOf("video" to 2, "audio" to 1, "text" to 1),
            row.clipsByKind,
        )
    }

    // ── default-1080p profile elides outputProfile ─────────────

    @Test fun defaultProfileElidesOutputProfileToNullForCompactRow() {
        // Pin: drift to "always populate" silently doubles the
        // row size on every project_metadata call (5 fields × N
        // tokens × every turn) — the elision is a token-budget
        // optimisation. DEFAULT_1080P used; outputProfile null.
        val row = decodeRow(runQuery(project()).data)
        assertNull(
            row.outputProfile,
            "default 1080p profile should elide; got ${row.outputProfile}",
        )
    }

    @Test fun nonDefaultProfilePopulatesOutputProfileWithFullDetails() {
        val custom = OutputProfile(
            resolution = Resolution(1280, 720),
            frameRate = FrameRate.FPS_24,
            videoCodec = "h265",
            audioCodec = "opus",
        )
        val row = decodeRow(runQuery(project(outputProfile = custom)).data)
        val profile = assertNotNull(row.outputProfile, "non-default profile should populate")
        assertEquals(1280, profile.resolutionWidth)
        assertEquals(720, profile.resolutionHeight)
        assertEquals(24, profile.frameRate)
        assertEquals("h265", profile.videoCodec)
        assertEquals("opus", profile.audioCodec)
    }

    // ── recent snapshots cap + DESC sort ────────────────────────

    @Test fun recentSnapshotsCappedAtFiveAndSortedDescByCapture() {
        // Pin: METADATA_MAX_RECENT_SNAPSHOTS = 5. 6 snapshots
        // with capturedAt 1..6 → row carries 5 newest (6,5,4,3,2),
        // oldest (1) dropped, and the order is newest-first.
        assertEquals(5, METADATA_MAX_RECENT_SNAPSHOTS, "constant pinned at 5")
        val emptyProject = Project(id = ProjectId("inner"), timeline = Timeline())
        val snapshots = (1..6L).map {
            ProjectSnapshot(
                id = ProjectSnapshotId("s$it"),
                label = "label-$it",
                capturedAtEpochMs = it,
                project = emptyProject,
            )
        }
        val row = decodeRow(runQuery(project(snapshots = snapshots)).data)
        assertEquals(6, row.snapshotCount, "snapshotCount reflects ALL snapshots")
        assertEquals(5, row.recentSnapshots.size, "recentSnapshots cap = 5")
        assertEquals(
            listOf(6L, 5L, 4L, 3L, 2L),
            row.recentSnapshots.map { it.capturedAtEpochMs },
            "DESC by capturedAtEpochMs; oldest (1) dropped",
        )
        assertEquals(
            listOf("s6", "s5", "s4", "s3", "s2"),
            row.recentSnapshots.map { it.id },
        )
    }

    @Test fun fewerThanCapSnapshotsAllPresentInDescOrder() {
        val emptyProject = Project(id = ProjectId("inner"), timeline = Timeline())
        val snapshots = (1..3L).map {
            ProjectSnapshot(
                id = ProjectSnapshotId("s$it"),
                label = "label-$it",
                capturedAtEpochMs = it,
                project = emptyProject,
            )
        }
        val row = decodeRow(runQuery(project(snapshots = snapshots)).data)
        assertEquals(3, row.recentSnapshots.size)
        assertEquals(listOf(3L, 2L, 1L), row.recentSnapshots.map { it.capturedAtEpochMs })
    }

    // ── source-nodes-by-kind sort ───────────────────────────────

    @Test fun sourceNodesByKindSortedAlphabeticallyForStableSummary() {
        // Pin: insertion order [zebra, alpha, mango] →
        // alphabetical [alpha, mango, zebra] in row map. Drift
        // to insertion-order would make summaryText
        // non-deterministic across runs.
        val nodes = listOf(
            SourceNode.create(id = SourceNodeId("z"), kind = "zebra"),
            SourceNode.create(id = SourceNodeId("a"), kind = "alpha"),
            SourceNode.create(id = SourceNodeId("m"), kind = "mango"),
        )
        val row = decodeRow(runQuery(project(source = Source(nodes = nodes))).data)
        assertEquals(3, row.sourceNodeCount)
        assertEquals(
            listOf("alpha", "mango", "zebra"),
            row.sourceNodesByKind.keys.toList(),
            "ascending by kind",
        )
    }

    // ── lockfile-by-tool sort ──────────────────────────────────

    @Test fun lockfileByToolSortedAlphabeticallyAndCountsCorrectly() {
        val lockfile = EagerLockfile(
            entries = listOf(
                lockfileEntry("h1", "generate_video", "av1"),
                lockfileEntry("h2", "generate_image", "ai1"),
                lockfileEntry("h3", "generate_image", "ai2"),
                lockfileEntry("h4", "generate_audio", "aa1"),
            ),
        )
        val row = decodeRow(runQuery(project(lockfile = lockfile)).data)
        assertEquals(4, row.lockfileEntryCount)
        assertEquals(
            listOf("generate_audio", "generate_image", "generate_video"),
            row.lockfileByTool.keys.toList(),
            "alphabetical tool ids",
        )
        assertEquals(2, row.lockfileByTool["generate_image"], "duplicate tool counted")
        assertEquals(1, row.lockfileByTool["generate_audio"])
    }

    // ── summaryText narrative ──────────────────────────────────

    @Test fun summaryTextSurfacesZeroSourceAndZeroLockfileMarkers() {
        // Pin: empty source / empty lockfile → human-readable "0
        // source nodes" / "0 lockfile entries" (no trailing
        // breakdown). A regression printing "(empty)" or
        // dropping the marker would corrupt the LLM-quotable
        // narrative. summaryText also doubles as outputForLlm.
        val out = runQuery(project()).outputForLlm
        assertTrue("0 source nodes" in out, "got: $out")
        assertTrue("0 lockfile entries" in out, "got: $out")
        assertTrue("0 snapshots." in out, "got: $out")
        assertTrue("Test Project" in out, "title surfaces; got: $out")
    }

    @Test fun summaryTextSurfacesResolutionAndFps() {
        // Pin: "1920x1080@30" pattern in summaryText. Drift to
        // "1920x1080" without fps would lose timing context.
        val out = runQuery(project()).outputForLlm
        assertTrue("1920x1080@30" in out, "default resolution surfaces; got: $out")
    }

    @Test fun summaryTextBreakdownSurfacesNonZeroKinds() {
        // Pin: tracks fragment shows only non-zero kinds via
        // `entries.filter { value > 0 }` — empty-timeline
        // shows "none", non-empty shows "1 video" etc.
        val timeline = Timeline(tracks = listOf(Track.Video(TrackId("vt"))))
        val out = runQuery(project(timeline = timeline)).outputForLlm
        assertTrue(
            "1 video" in out,
            "non-empty kind surfaces; got: $out",
        )
        assertTrue(
            "0 audio" !in out,
            "zero-count kind suppressed in tracks fragment; got: $out",
        )
    }

    @Test fun summaryTextEmptyTracksShowsNoneSentinel() {
        // Pin: zero tracks → tracksFragment = "none" (not
        // empty string, not "0 tracks").
        val out = runQuery(project()).outputForLlm
        assertTrue("(none)" in out, "0-track sentinel; got: $out")
    }

    // ── store inconsistency ───────────────────────────────────

    @Test fun missingCatalogSummaryRaisesStoreInconsistencyError() {
        // Pin: projects.summary(project.id) returning null is a
        // hard error — the only way this happens is a store
        // inconsistency (project loaded but no catalog row).
        // The error message names the project id so the
        // operator can grep for it.
        val ex = assertFailsWith<IllegalStateException> {
            runQuery(project(id = "ghost"), FakeProjectStore(returnNullSummary = true))
        }
        assertTrue(
            "ghost" in (ex.message ?: ""),
            "error names the project id; got: ${ex.message}",
        )
        assertTrue(
            "store inconsistency" in (ex.message ?: ""),
            "error mentions store inconsistency; got: ${ex.message}",
        )
    }

    // ── output framing ─────────────────────────────────────────

    @Test fun outputCarriesProjectIdSelectAndSingleRow() {
        // project_metadata is a single-row drill-down — total
        // and returned both fixed at 1 regardless of project
        // shape.
        val result = runQuery(project(id = "p"))
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_PROJECT_METADATA, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(1, result.data.rows.size)
    }

    @Test fun titleIncludesProjectTitleNotProjectId() {
        // Pin: title format = "project_query project_metadata
        // <title>" (uses ProjectSummary.title, not the
        // ProjectId, since title is the human label). Lets
        // session-history scrolling identify the project.
        val store = FakeProjectStore(title = "My Movie")
        val result = runQuery(project(id = "internal-id"), store)
        assertEquals(
            "project_query project_metadata My Movie",
            result.title,
        )
    }

    @Test fun timelineDurationSecondsFormattedAsDouble() {
        // Pin: duration in seconds with millisecond precision
        // → 1500ms timeline → 1.5 seconds. A regression
        // dropping decimals (`/1000` int division) would lose
        // sub-second timing in the row.
        // Timeline.duration is a stored field (defaults to ZERO),
        // not derived from clip ranges. Set it explicitly to
        // exercise the millisecond-precision conversion.
        val timeline = Timeline(duration = 1.5.seconds)
        val row = decodeRow(runQuery(project(timeline = timeline)).data)
        assertEquals(1.5, row.timelineDurationSeconds)
    }

    @Test fun assetCountAndSourceRevisionAndRenderCacheCountSurface() {
        // Pin: row carries `assetCount`, `sourceRevision`, and
        // `renderCacheEntryCount` from project state. These
        // came from the deleted get_project_state and live as
        // back-compat default fields (= 0 by default to keep
        // older serialised rows decodable).
        val asset = MediaAsset(
            id = AssetId("a1"),
            source = MediaSource.File("/tmp/test.mp4"),
            metadata = MediaMetadata(duration = 5.seconds),
        )
        val nodes = listOf(SourceNode.create(id = SourceNodeId("n"), kind = "k"))
        val source = Source(nodes = nodes, revision = 7L)
        val row = decodeRow(
            runQuery(project(assets = listOf(asset), source = source)).data,
        )
        assertEquals(1, row.assetCount)
        assertEquals(1, row.sourceNodeCount)
        assertEquals(7L, row.sourceRevision, "non-default revision surfaces")
        assertEquals(0, row.renderCacheEntryCount, "empty render cache pinned at 0")
    }
}
