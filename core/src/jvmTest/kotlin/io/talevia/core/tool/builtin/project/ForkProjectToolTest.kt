package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.addNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ForkProjectToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private fun fakeAsset(id: AssetId): MediaAsset = MediaAsset(
        id = id,
        source = MediaSource.File("/tmp/${id.value}.mp4"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    private fun videoClip(id: String, asset: AssetId): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 2.seconds),
        sourceRange = TimeRange(0.seconds, 2.seconds),
        assetId = asset,
    )

    @Test fun forksFromCurrentStateWhenSnapshotIdNull() = runTest {
        val rig = rig()
        val asset = AssetId("a-1")
        rig.store.upsert(
            "test",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c-1", asset))))),
                assets = listOf(fakeAsset(asset)),
            ),
        )
        val tool = ForkProjectTool(rig.store)

        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "What if alt cut",
            ),
            rig.ctx,
        )

        assertEquals("p", out.data.sourceProjectId)
        assertEquals("proj-what-if-alt-cut", out.data.newProjectId)
        assertNull(out.data.branchedFromSnapshotId)
        assertEquals(1, out.data.clipCount)

        val fork = rig.store.get(ProjectId("proj-what-if-alt-cut"))!!
        assertEquals(ProjectId("proj-what-if-alt-cut"), fork.id)
        assertEquals(1, fork.timeline.tracks.flatMap { it.clips }.size)
        assertEquals(1, fork.assets.size)
        assertTrue(fork.snapshots.isEmpty(), "fork must start with a clean snapshots list")

        // Source project unchanged.
        val source = rig.store.get(ProjectId("p"))!!
        assertEquals(1, source.timeline.tracks.flatMap { it.clips }.size)
    }

    @Test fun forksFromExplicitSnapshotPayload() = runTest {
        val rig = rig()
        val originalAsset = AssetId("a-original")
        val snapshotAsset = AssetId("a-snapshot")
        // Source project: 2 clips currently, but snapshot v1 only has 1.
        val v1Captured = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c-snap", snapshotAsset)))),
            ),
            assets = listOf(fakeAsset(snapshotAsset)),
        )
        rig.store.upsert(
            "test",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(videoClip("c-current-1", originalAsset), videoClip("c-current-2", originalAsset)),
                        ),
                    ),
                ),
                assets = listOf(fakeAsset(originalAsset)),
                snapshots = listOf(
                    ProjectSnapshot(ProjectSnapshotId("snap-v1"), "v1", 1_000L, v1Captured),
                ),
            ),
        )
        val tool = ForkProjectTool(rig.store)

        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "Branch off v1",
                snapshotId = "snap-v1",
            ),
            rig.ctx,
        )

        assertEquals("snap-v1", out.data.branchedFromSnapshotId)
        assertEquals(1, out.data.clipCount, "fork must reflect the snapshot, not current state")

        val fork = rig.store.get(ProjectId("proj-branch-off-v1"))!!
        val clip = fork.timeline.tracks.flatMap { it.clips }.single()
        assertEquals(snapshotAsset, (clip as Clip.Video).assetId)
        assertTrue(fork.snapshots.isEmpty())
    }

    @Test fun acceptsExplicitNewProjectId() = runTest {
        val rig = rig()
        rig.store.upsert(
            "test",
            Project(id = ProjectId("p"), timeline = Timeline()),
        )
        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "any title",
                newProjectId = "my-explicit-fork-id",
            ),
            rig.ctx,
        )
        assertEquals("my-explicit-fork-id", out.data.newProjectId)
        assertNotNull(rig.store.get(ProjectId("my-explicit-fork-id")))
    }

    @Test fun failsLoudOnMissingSourceProject() = runTest {
        val rig = rig()
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ForkProjectTool.Input(sourceProjectId = "ghost", newTitle = "x"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun failsLoudOnMissingSnapshot() = runTest {
        val rig = rig()
        rig.store.upsert("test", Project(id = ProjectId("p"), timeline = Timeline()))
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ForkProjectTool.Input(
                    sourceProjectId = "p",
                    newTitle = "x",
                    snapshotId = "ghost-snap",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost-snap"), ex.message)
    }

    @Test fun failsLoudOnDuplicateNewProjectId() = runTest {
        val rig = rig()
        rig.store.upsert("source", Project(id = ProjectId("src"), timeline = Timeline()))
        rig.store.upsert("dest", Project(id = ProjectId("dest"), timeline = Timeline()))
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ForkProjectTool.Input(
                    sourceProjectId = "src",
                    newTitle = "x",
                    newProjectId = "dest",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("dest"), ex.message)
    }

    @Test fun rejectsBlankTitle() = runTest {
        val rig = rig()
        rig.store.upsert("source", Project(id = ProjectId("src"), timeline = Timeline()))
        val tool = ForkProjectTool(rig.store)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ForkProjectTool.Input(sourceProjectId = "src", newTitle = "  "),
                rig.ctx,
            )
        }
    }

    @Test fun forkPreservesSourceDagNodes() = runTest {
        val rig = rig()
        val charNodeId = SourceNodeId("mei")
        val sourceWithNode = Project(
            id = ProjectId("p-src"),
            timeline = Timeline(),
            source = Source.EMPTY.addNode(
                SourceNode(
                    id = charNodeId,
                    kind = "core.consistency.character_ref",
                    body = buildJsonObject { },
                ),
            ),
        )
        rig.store.upsert("source", sourceWithNode)

        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(sourceProjectId = "p-src", newTitle = "Fork with nodes"),
            rig.ctx,
        )

        val fork = rig.store.get(ProjectId(out.data.newProjectId))!!
        assertNotNull(fork.source.byId[charNodeId], "fork must carry source DAG nodes from the original")
        assertEquals(1, fork.source.nodes.size)

        // Originals are unchanged.
        val original = rig.store.get(ProjectId("p-src"))!!
        assertEquals(1, original.source.nodes.size)
    }

    @Test fun forkPreservesLockfileEntries() = runTest {
        val rig = rig()
        val asset = AssetId("a-gen")
        val bindingNodeId = SourceNodeId("style-node")
        val entry = LockfileEntry(
            inputHash = "h-1",
            toolId = "generate_image",
            assetId = asset,
            provenance = GenerationProvenance(
                providerId = "openai",
                modelId = "gpt-image-1",
                modelVersion = null,
                seed = 42L,
                parameters = buildJsonObject { },
                createdAtEpochMs = 1_000L,
            ),
            sourceBinding = setOf(bindingNodeId),
        )
        val sourceProject = Project(
            id = ProjectId("p-lf"),
            timeline = Timeline(),
            lockfile = Lockfile.EMPTY.append(entry),
        )
        rig.store.upsert("source", sourceProject)

        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(sourceProjectId = "p-lf", newTitle = "Fork with lockfile"),
            rig.ctx,
        )

        val fork = rig.store.get(ProjectId(out.data.newProjectId))!!
        assertEquals(1, fork.lockfile.entries.size, "fork must carry lockfile entries for cache reuse")
        assertEquals("h-1", fork.lockfile.entries.single().inputHash)
        assertEquals(asset, fork.lockfile.entries.single().assetId)
    }

    @Test fun forkIsIndependentOfOriginalAfterMutation() = runTest {
        val rig = rig()
        val asset = AssetId("a-orig")
        rig.store.upsert(
            "source",
            Project(
                id = ProjectId("p-ind"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v"), listOf(videoClip("c-1", asset))))),
                assets = listOf(fakeAsset(asset)),
            ),
        )
        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(sourceProjectId = "p-ind", newTitle = "Independence test"),
            rig.ctx,
        )
        val forkId = ProjectId(out.data.newProjectId)

        // Mutate the fork — add a second clip.
        rig.store.mutate(forkId) { p ->
            p.copy(
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v"),
                            listOf(videoClip("c-1", asset), videoClip("c-2", asset)),
                        ),
                    ),
                ),
            )
        }

        val original = rig.store.get(ProjectId("p-ind"))!!
        assertEquals(1, original.timeline.tracks.flatMap { it.clips }.size, "original must not be affected by fork mutations")
        val forked = rig.store.get(forkId)!!
        assertEquals(2, forked.timeline.tracks.flatMap { it.clips }.size)
    }

    // ── variantSpec — VISION §6 "30s / vertical variant" reshape ──

    /** Source timeline: three 2s video clips at [0,2), [2,4), [4,6) on a single video track. */
    private suspend fun seedThreeClipSource(store: SqlDelightProjectStore, pid: String = "p-var-src"): ProjectId {
        val asset = AssetId("a-long")
        store.upsert(
            "source",
            Project(
                id = ProjectId(pid),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v"),
                            listOf(
                                Clip.Video(
                                    id = ClipId("c-1"),
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = asset,
                                ),
                                Clip.Video(
                                    id = ClipId("c-2"),
                                    timeRange = TimeRange(2.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = asset,
                                ),
                                Clip.Video(
                                    id = ClipId("c-3"),
                                    timeRange = TimeRange(4.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = asset,
                                ),
                            ),
                        ),
                    ),
                    duration = 6.seconds,
                ),
                assets = listOf(fakeAsset(asset)),
            ),
        )
        return ProjectId(pid)
    }

    @Test fun variantSpecAspectRatioReframesResolution() = runTest {
        val rig = rig()
        seedThreeClipSource(rig.store)
        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p-var-src",
                newTitle = "Vertical Cut",
                newProjectId = "p-var-vert",
                variantSpec = ForkProjectTool.VariantSpec(aspectRatio = "9:16"),
            ),
            rig.ctx,
        )
        val forked = rig.store.get(ProjectId("p-var-vert"))!!
        assertEquals(1080, forked.timeline.resolution.width)
        assertEquals(1920, forked.timeline.resolution.height)
        assertEquals(1080, forked.outputProfile.resolution.width)
        assertEquals(1920, forked.outputProfile.resolution.height)
        assertEquals(1080, out.data.variantResolutionWidth)
        assertEquals(1920, out.data.variantResolutionHeight)
        // All three clips preserved — aspect reframe doesn't trim.
        assertEquals(3, forked.timeline.tracks.flatMap { it.clips }.size)
        assertEquals(ProjectId("p-var-src"), forked.parentProjectId)
    }

    @Test fun variantSpecDurationDropsTailClipsAndTruncatesStraddlers() = runTest {
        val rig = rig()
        seedThreeClipSource(rig.store)
        val tool = ForkProjectTool(rig.store)
        // Cap at 3s: c-1 (0-2) survives whole, c-2 (2-4) truncates to 2-3 (1s), c-3 (4-6) drops.
        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p-var-src",
                newTitle = "Short Cut",
                newProjectId = "p-var-short",
                variantSpec = ForkProjectTool.VariantSpec(durationSecondsMax = 3.0),
            ),
            rig.ctx,
        )
        assertEquals(1, out.data.clipsDroppedByTrim)
        assertEquals(1, out.data.clipsTruncatedByTrim)
        val forked = rig.store.get(ProjectId("p-var-short"))!!
        val clips = forked.timeline.tracks.flatMap { it.clips }
        assertEquals(setOf("c-1", "c-2"), clips.map { it.id.value }.toSet())
        val c2 = clips.single { it.id.value == "c-2" } as Clip.Video
        assertEquals(1.seconds, c2.timeRange.duration, "straddler timeRange truncates to cap-start")
        assertEquals(1.seconds, c2.sourceRange.duration, "sourceRange truncates in lock-step with timeline")
        assertEquals(3.seconds, forked.timeline.duration)
    }

    @Test fun variantSpecCombinedAspectAndDuration() = runTest {
        val rig = rig()
        seedThreeClipSource(rig.store)
        val tool = ForkProjectTool(rig.store)
        tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p-var-src",
                newTitle = "Square Short",
                newProjectId = "p-var-sqshort",
                variantSpec = ForkProjectTool.VariantSpec(
                    aspectRatio = "1:1",
                    durationSecondsMax = 2.0,
                ),
            ),
            rig.ctx,
        )
        val forked = rig.store.get(ProjectId("p-var-sqshort"))!!
        assertEquals(1080, forked.timeline.resolution.width)
        assertEquals(1080, forked.timeline.resolution.height)
        // Cap=2s: c-1 (0-2, end==cap) survives whole (not straddler), others drop.
        val clips = forked.timeline.tracks.flatMap { it.clips }
        assertEquals(listOf("c-1"), clips.map { it.id.value })
    }

    @Test fun variantSpecRejectsUnknownAspect() = runTest {
        val rig = rig()
        seedThreeClipSource(rig.store)
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ForkProjectTool.Input(
                    sourceProjectId = "p-var-src",
                    newTitle = "Bad Aspect",
                    newProjectId = "p-var-badasp",
                    variantSpec = ForkProjectTool.VariantSpec(aspectRatio = "3:7"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("aspectRatio"), ex.message)
    }

    @Test fun variantSpecRejectsNonPositiveDuration() = runTest {
        val rig = rig()
        seedThreeClipSource(rig.store)
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ForkProjectTool.Input(
                    sourceProjectId = "p-var-src",
                    newTitle = "Zero Dur",
                    newProjectId = "p-var-zerodur",
                    variantSpec = ForkProjectTool.VariantSpec(durationSecondsMax = 0.0),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("durationSecondsMax"), ex.message)
    }

    @Test fun plainForkHasNoVariantMetadata() = runTest {
        // Regression guard: fork without variantSpec must leave the variant-related
        // Output fields at their defaults.
        val rig = rig()
        seedThreeClipSource(rig.store)
        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p-var-src",
                newTitle = "Plain Fork",
                newProjectId = "p-var-plain",
            ),
            rig.ctx,
        )
        assertNull(out.data.appliedVariantSpec)
        assertNull(out.data.variantResolutionWidth)
        assertNull(out.data.variantResolutionHeight)
        assertEquals(0, out.data.clipsDroppedByTrim)
        assertEquals(0, out.data.clipsTruncatedByTrim)
        // Parent pointer still set — even plain forks record lineage.
        val forked = rig.store.get(ProjectId("p-var-plain"))!!
        assertEquals(ProjectId("p-var-src"), forked.parentProjectId)
    }

    @Test fun variantOnEmptyTimelineIsNoopSafe() = runTest {
        val rig = rig()
        rig.store.upsert("empty", Project(id = ProjectId("p-empty"), timeline = Timeline()))
        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p-empty",
                newTitle = "Empty Vertical",
                newProjectId = "p-empty-v",
                variantSpec = ForkProjectTool.VariantSpec(
                    aspectRatio = "9:16",
                    durationSecondsMax = 10.0,
                ),
            ),
            rig.ctx,
        )
        assertEquals(0, out.data.clipsDroppedByTrim)
        assertEquals(0, out.data.clipsTruncatedByTrim)
        val forked = rig.store.get(ProjectId("p-empty-v"))!!
        assertEquals(1080, forked.timeline.resolution.width)
        assertEquals(1920, forked.timeline.resolution.height)
    }
}
