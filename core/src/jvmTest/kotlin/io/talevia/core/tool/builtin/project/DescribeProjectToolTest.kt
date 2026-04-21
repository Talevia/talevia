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
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TextStyle
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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DescribeProjectToolTest {

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

    private fun videoClip(id: String, asset: AssetId, start: Long = 0, dur: Long = 2): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, dur.seconds),
            sourceRange = TimeRange(0.seconds, dur.seconds),
            assetId = asset,
        )

    private fun audioClip(id: String, asset: AssetId, start: Long = 0, dur: Long = 2): Clip.Audio =
        Clip.Audio(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, dur.seconds),
            sourceRange = TimeRange(0.seconds, dur.seconds),
            assetId = asset,
        )

    private fun textClip(id: String, start: Long = 0, dur: Long = 2, text: String = "hi"): Clip.Text =
        Clip.Text(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, dur.seconds),
            text = text,
            style = TextStyle(),
        )

    private fun fakeAsset(id: AssetId): MediaAsset = MediaAsset(
        id = id,
        source = MediaSource.File("/tmp/${id.value}.mp4"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    private fun fakeProvenance(): GenerationProvenance = GenerationProvenance(
        providerId = "fake",
        modelId = "fake-model",
        modelVersion = null,
        seed = 1L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    @Test fun emptyProjectReportsAllZeroCountsAndNonEmptySummary() = runTest {
        val rig = rig()
        rig.store.upsert("empty", Project(id = ProjectId("p"), timeline = Timeline()))

        val out = DescribeProjectTool(rig.store).execute(
            DescribeProjectTool.Input(projectId = "p"),
            rig.ctx,
        ).data

        assertEquals("p", out.projectId)
        assertEquals("empty", out.title)
        assertEquals(0, out.trackCount)
        assertEquals(0, out.clipCount)
        assertEquals(0, out.assetCount)
        assertEquals(0, out.sourceNodeCount)
        assertEquals(0, out.lockfileEntryCount)
        assertEquals(0, out.snapshotCount)
        assertEquals(0.0, out.timelineDurationSeconds)
        // Fixed-bucket maps always present — even with all zeros.
        assertEquals(mapOf("video" to 0, "audio" to 0, "subtitle" to 0, "effect" to 0), out.tracksByKind)
        assertEquals(mapOf("video" to 0, "audio" to 0, "text" to 0), out.clipsByKind)
        assertTrue(out.sourceNodesByKind.isEmpty())
        assertTrue(out.lockfileByTool.isEmpty())
        assertNull(out.outputProfile)
        assertTrue(out.summaryText.isNotEmpty(), "summaryText must be non-empty even on empty projects")
        assertTrue(out.summaryText.contains("empty"), "summary includes title: ${out.summaryText}")
    }

    @Test fun timelineOnlyPopulatesTrackAndClipBreakdowns() = runTest {
        val rig = rig()
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        listOf(
                            videoClip("c-1", AssetId("a-1"), start = 0, dur = 3),
                            videoClip("c-2", AssetId("a-2"), start = 3, dur = 2),
                        ),
                    ),
                    Track.Video(TrackId("v2"), listOf(videoClip("c-3", AssetId("a-3"), start = 0, dur = 4))),
                    Track.Audio(TrackId("au1"), listOf(audioClip("c-4", AssetId("a-4"), start = 0, dur = 5))),
                    Track.Subtitle(TrackId("sub1"), listOf(textClip("c-5", start = 0, dur = 2, text = "hello world"))),
                ),
                duration = 5.seconds,
            ),
            assets = listOf(
                fakeAsset(AssetId("a-1")),
                fakeAsset(AssetId("a-2")),
                fakeAsset(AssetId("a-3")),
                fakeAsset(AssetId("a-4")),
            ),
        )
        rig.store.upsert("vlog", project)

        val out = DescribeProjectTool(rig.store).execute(
            DescribeProjectTool.Input(projectId = "p"),
            rig.ctx,
        ).data

        assertEquals(4, out.trackCount)
        assertEquals(5, out.clipCount)
        assertEquals(5.0, out.timelineDurationSeconds)
        assertEquals(2, out.tracksByKind["video"])
        assertEquals(1, out.tracksByKind["audio"])
        assertEquals(1, out.tracksByKind["subtitle"])
        assertEquals(0, out.tracksByKind["effect"])
        assertEquals(3, out.clipsByKind["video"])
        assertEquals(1, out.clipsByKind["audio"])
        assertEquals(1, out.clipsByKind["text"])
        assertEquals(4, out.assetCount)
        assertTrue(out.summaryText.contains("4 tracks"), out.summaryText)
        assertTrue(out.summaryText.contains("5 clips"), out.summaryText)
    }

    @Test fun sourceNodesByKindCountsEachKind() = runTest {
        val rig = rig()
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(),
            source = Source.EMPTY
                .addNode(SourceNode.create(SourceNodeId("mei"), kind = "core.consistency.character_ref"))
                .addNode(SourceNode.create(SourceNodeId("rin"), kind = "core.consistency.character_ref"))
                .addNode(SourceNode.create(SourceNodeId("ken"), kind = "core.consistency.character_ref"))
                .addNode(SourceNode.create(SourceNodeId("bible1"), kind = "core.consistency.style_bible"))
                .addNode(SourceNode.create(SourceNodeId("bible2"), kind = "core.consistency.style_bible"))
                .addNode(SourceNode.create(SourceNodeId("scene1"), kind = "narrative.scene")),
        )
        rig.store.upsert("story", project)

        val out = DescribeProjectTool(rig.store).execute(
            DescribeProjectTool.Input(projectId = "p"),
            rig.ctx,
        ).data

        assertEquals(6, out.sourceNodeCount)
        assertEquals(3, out.sourceNodesByKind["core.consistency.character_ref"])
        assertEquals(2, out.sourceNodesByKind["core.consistency.style_bible"])
        assertEquals(1, out.sourceNodesByKind["narrative.scene"])
        assertTrue(
            out.summaryText.contains("6 source nodes") &&
                out.summaryText.contains("character_ref"),
            out.summaryText,
        )
    }

    @Test fun lockfileByToolGroupsByToolIdWithCounts() = runTest {
        val rig = rig()
        val entries = listOf(
            "generate_image", "generate_image", "generate_image",
            "synthesize_speech", "synthesize_speech",
            "generate_music",
        ).mapIndexed { idx, toolId ->
            LockfileEntry(
                inputHash = "h-$idx",
                toolId = toolId,
                assetId = AssetId("a-$idx"),
                provenance = fakeProvenance(),
            )
        }
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(),
            lockfile = entries.fold(Lockfile.EMPTY) { acc, e -> acc.append(e) },
        )
        rig.store.upsert("studio", project)

        val out = DescribeProjectTool(rig.store).execute(
            DescribeProjectTool.Input(projectId = "p"),
            rig.ctx,
        ).data

        assertEquals(6, out.lockfileEntryCount)
        assertEquals(3, out.lockfileByTool["generate_image"])
        assertEquals(2, out.lockfileByTool["synthesize_speech"])
        assertEquals(1, out.lockfileByTool["generate_music"])
        assertTrue(out.summaryText.contains("6 lockfile entries"), out.summaryText)
        assertTrue(out.summaryText.contains("generate_image:3"), out.summaryText)
    }

    @Test fun snapshotCountReflectsSavedSnapshots() = runTest {
        val rig = rig()
        val captured = Project(id = ProjectId("p"), timeline = Timeline())
        val project = captured.copy(
            snapshots = listOf(
                ProjectSnapshot(ProjectSnapshotId("s1"), "v1", 1_000L, captured),
                ProjectSnapshot(ProjectSnapshotId("s2"), "v2", 2_000L, captured),
                ProjectSnapshot(ProjectSnapshotId("s3"), "v3", 3_000L, captured),
            ),
        )
        rig.store.upsert("snap-test", project)

        val out = DescribeProjectTool(rig.store).execute(
            DescribeProjectTool.Input(projectId = "p"),
            rig.ctx,
        ).data

        assertEquals(3, out.snapshotCount)
        assertTrue(out.summaryText.contains("3 snapshots"), out.summaryText)
    }

    @Test fun outputProfileIsNullWhenDefaultAndSetWhenCustom() = runTest {
        val rig = rig()
        rig.store.upsert("default-profile", Project(id = ProjectId("default"), timeline = Timeline()))
        rig.store.upsert(
            "custom-profile",
            Project(
                id = ProjectId("custom"),
                timeline = Timeline(),
                outputProfile = OutputProfile(
                    resolution = Resolution(3840, 2160),
                    frameRate = FrameRate(60),
                    videoCodec = "h265",
                    audioCodec = "opus",
                ),
            ),
        )
        val tool = DescribeProjectTool(rig.store)

        val defaultOut = tool.execute(
            DescribeProjectTool.Input(projectId = "default"),
            rig.ctx,
        ).data
        assertNull(defaultOut.outputProfile, "default 1080p profile reads as unset/null")

        val customOut = tool.execute(
            DescribeProjectTool.Input(projectId = "custom"),
            rig.ctx,
        ).data
        val profile = customOut.outputProfile
        assertNotNull(profile, "custom profile must surface")
        assertEquals(3840, profile.resolutionWidth)
        assertEquals(2160, profile.resolutionHeight)
        assertEquals(60, profile.frameRate)
        assertEquals("h265", profile.videoCodec)
        assertEquals("opus", profile.audioCodec)
        assertTrue(customOut.summaryText.contains("3840x2160@60"), customOut.summaryText)
    }

    @Test fun missingProjectThrows() = runTest {
        val rig = rig()
        val tool = DescribeProjectTool(rig.store)
        assertFailsWith<IllegalStateException> {
            tool.execute(DescribeProjectTool.Input(projectId = "nope"), rig.ctx)
        }
    }

    @Test fun summaryTextIsCompactAndEchoesKeyFacts() = runTest {
        val rig = rig()
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("v1"), listOf(videoClip("c-1", AssetId("a-1")))),
                    Track.Audio(TrackId("au1"), listOf(audioClip("c-2", AssetId("a-2")))),
                ),
                duration = 4.seconds,
            ),
            assets = listOf(fakeAsset(AssetId("a-1")), fakeAsset(AssetId("a-2"))),
            source = Source.EMPTY.addNode(
                SourceNode.create(SourceNodeId("mei"), kind = "core.consistency.character_ref"),
            ),
            lockfile = Lockfile.EMPTY.append(
                LockfileEntry(
                    inputHash = "h",
                    toolId = "generate_image",
                    assetId = AssetId("a-1"),
                    provenance = fakeProvenance(),
                ),
            ),
        )
        rig.store.upsert("my-vlog", project)

        val out = DescribeProjectTool(rig.store).execute(
            DescribeProjectTool.Input(projectId = "p"),
            rig.ctx,
        ).data

        val text = out.summaryText
        // Reasonable ceiling to catch explosions (spec says "compact ~300-char").
        assertTrue(text.length <= 500, "summaryText should stay compact: ${text.length} chars — $text")
        assertTrue(text.contains("my-vlog"), text)
        assertTrue(text.contains("2 tracks"), text)
        assertTrue(text.contains("2 clips"), text)
        assertTrue(text.contains("1 source nodes") || text.contains("source"), text)
        assertTrue(text.contains("1 lockfile") || text.contains("generate_image"), text)
    }

    @Test fun projectIdOmittedFallsBackToSessionBinding() = runTest {
        val rig = rig()
        rig.store.upsert("empty", Project(id = ProjectId("p"), timeline = Timeline()))
        val ctxBound = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
            currentProjectId = ProjectId("p"),
        )
        val out = DescribeProjectTool(rig.store).execute(
            DescribeProjectTool.Input(), // projectId omitted
            ctxBound,
        ).data
        assertEquals("p", out.projectId)
    }

    @Test fun unboundSessionAndOmittedProjectIdFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            DescribeProjectTool(rig.store).execute(DescribeProjectTool.Input(), rig.ctx)
        }
        assertTrue(ex.message!!.contains("switch_project"), ex.message)
    }
}
