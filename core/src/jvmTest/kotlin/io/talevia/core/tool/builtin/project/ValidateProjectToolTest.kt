package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises [ValidateProjectTool] on the full rule vocabulary — passed-case,
 * dangling asset, dangling source binding, volume out of range, fade overlap,
 * non-positive duration, timeline duration mismatch. One test per code so a
 * rule regression points at exactly the rule that broke.
 */
class ValidateProjectToolTest {

    private data class Rig(
        val tool: ValidateProjectTool,
        val ctx: ToolContext,
    )

    private fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        kotlinx.coroutines.runBlocking { store.upsert("t", project) }
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        return Rig(ValidateProjectTool(store), ctx)
    }

    private fun assetWithId(id: String, duration: Duration = 10.seconds): MediaAsset =
        MediaAsset(
            id = AssetId(id),
            source = MediaSource.File("/tmp/$id.mp4"),
            metadata = MediaMetadata(
                duration = duration,
                resolution = Resolution(1920, 1080),
                frameRate = FrameRate.FPS_30,
            ),
        )

    private fun videoClip(id: String, assetId: String, start: Duration, duration: Duration): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start, duration),
            sourceRange = TimeRange(Duration.ZERO, duration),
            assetId = AssetId(assetId),
        )

    private fun baseProject(
        clips: List<Clip> = emptyList(),
        assets: List<MediaAsset> = emptyList(),
        source: Source = Source.EMPTY,
        timelineDuration: Duration? = null,
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("v1"), clips))
        val duration = timelineDuration
            ?: clips.maxOfOrNull { it.timeRange.end }
            ?: Duration.ZERO
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks, duration = duration),
            assets = assets,
            source = source,
        )
    }

    @Test fun passesWhenEverythingResolves() = runTest {
        val asset = assetWithId("a")
        val project = baseProject(
            clips = listOf(videoClip("c1", "a", Duration.ZERO, 3.seconds)),
            assets = listOf(asset),
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertTrue(out.data.passed)
        assertEquals(0, out.data.issueCount)
    }

    @Test fun flagsDanglingAssetId() = runTest {
        val project = baseProject(
            clips = listOf(videoClip("c1", "missing", Duration.ZERO, 3.seconds)),
            assets = emptyList(),
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertFalse(out.data.passed)
        val issue = out.data.issues.single { it.code == "dangling-asset" }
        assertEquals("error", issue.severity)
        assertEquals("c1", issue.clipId)
        assertEquals("v1", issue.trackId)
    }

    @Test fun flagsDanglingSourceBinding() = runTest {
        val asset = assetWithId("a")
        val v = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
            sourceBinding = setOf(SourceNodeId("ghost")),
        )
        val project = baseProject(clips = listOf(v), assets = listOf(asset))
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertFalse(out.data.passed)
        val issue = out.data.issues.single { it.code == "dangling-source-binding" }
        assertTrue("ghost" in issue.message, issue.message)
    }

    @Test fun acceptsSourceBindingThatResolves() = runTest {
        val asset = assetWithId("a")
        val node = SourceNode(
            id = SourceNodeId("mei"),
            kind = "core.consistency.character_ref",
            body = JsonObject(emptyMap()),
        )
        val v = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
            sourceBinding = setOf(SourceNodeId("mei")),
        )
        val project = baseProject(
            clips = listOf(v),
            assets = listOf(asset),
            source = Source(nodes = listOf(node)),
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertTrue(out.data.passed)
    }

    @Test fun flagsAudioVolumeOutOfRange() = runTest {
        val a = assetWithId("voice")
        val hot = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("voice"),
            volume = 7f,
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Audio(TrackId("at"), listOf(hot))),
                duration = 5.seconds,
            ),
            assets = listOf(a),
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        val issue = out.data.issues.single { it.code == "volume-range" }
        assertEquals("error", issue.severity)
        assertEquals("a1", issue.clipId)
    }

    @Test fun flagsFadeOverlap() = runTest {
        val a = assetWithId("voice")
        val badFade = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 2.seconds),
            sourceRange = TimeRange(Duration.ZERO, 2.seconds),
            assetId = AssetId("voice"),
            fadeInSeconds = 1.5f,
            fadeOutSeconds = 1.5f,
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Audio(TrackId("at"), listOf(badFade))),
                duration = 2.seconds,
            ),
            assets = listOf(a),
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        val issue = out.data.issues.single { it.code == "fade-overlap" }
        assertEquals("error", issue.severity)
    }

    @Test fun flagsNegativeFade() = runTest {
        val a = assetWithId("voice")
        val neg = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("voice"),
            fadeInSeconds = -1f,
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Audio(TrackId("at"), listOf(neg))),
                duration = 5.seconds,
            ),
            assets = listOf(a),
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertTrue(out.data.issues.any { it.code == "fade-negative" })
    }

    @Test fun warnsOnTimelineDurationMismatch() = runTest {
        val asset = assetWithId("a")
        val v = videoClip("c1", "a", Duration.ZERO, 5.seconds)
        // Timeline duration shorter than max clip end — engines would truncate.
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v1"), listOf(v))),
                duration = 2.seconds,
            ),
            assets = listOf(asset),
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        val issue = out.data.issues.single { it.code == "duration-mismatch" }
        assertEquals("warn", issue.severity)
        assertTrue(out.data.passed) // warnings do not block
    }

    @Test fun flagsDanglingParentRef() = runTest {
        val child = SourceNode(
            id = SourceNodeId("child"),
            kind = "narrative.scene",
            body = JsonObject(emptyMap()),
            parents = listOf(SourceRef(SourceNodeId("ghost-parent"))),
        )
        val project = baseProject(source = Source(nodes = listOf(child)))
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertFalse(out.data.passed)
        val issue = out.data.issues.single { it.code == "source-parent-dangling" }
        assertEquals("error", issue.severity)
        assertTrue("child" in issue.message, issue.message)
        assertTrue("ghost-parent" in issue.message, issue.message)
    }

    @Test fun flagsSourceCycle() = runTest {
        // a → b → c → a
        val a = SourceNode(
            id = SourceNodeId("a"),
            kind = "narrative.scene",
            body = JsonObject(emptyMap()),
            parents = listOf(SourceRef(SourceNodeId("c"))),
        )
        val b = SourceNode(
            id = SourceNodeId("b"),
            kind = "narrative.scene",
            body = JsonObject(emptyMap()),
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val c = SourceNode(
            id = SourceNodeId("c"),
            kind = "narrative.scene",
            body = JsonObject(emptyMap()),
            parents = listOf(SourceRef(SourceNodeId("b"))),
        )
        val project = baseProject(source = Source(nodes = listOf(a, b, c)))
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertFalse(out.data.passed)
        val cycleIssues = out.data.issues.filter { it.code == "source-parent-cycle" }
        assertTrue(cycleIssues.isNotEmpty(), "must emit at least one source-parent-cycle issue")
        // The rendered message should mention all three nodes in the cycle.
        val combined = cycleIssues.joinToString { it.message }
        assertTrue("a" in combined && "b" in combined && "c" in combined, combined)
    }

    @Test fun acceptsAcyclicDag() = runTest {
        val world = SourceNode(
            id = SourceNodeId("world"),
            kind = "narrative.world",
            body = JsonObject(emptyMap()),
        )
        val scene = SourceNode(
            id = SourceNodeId("scene"),
            kind = "narrative.scene",
            body = JsonObject(emptyMap()),
            parents = listOf(SourceRef(SourceNodeId("world"))),
        )
        val shot = SourceNode(
            id = SourceNodeId("shot"),
            kind = "narrative.shot",
            body = JsonObject(emptyMap()),
            parents = listOf(SourceRef(SourceNodeId("scene"))),
        )
        val project = baseProject(source = Source(nodes = listOf(world, scene, shot)))
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertTrue(out.data.passed)
        assertTrue(out.data.issues.none { it.code.startsWith("source-") })
    }

    @Test fun selfLoopIsCycle() = runTest {
        val self = SourceNode(
            id = SourceNodeId("self"),
            kind = "narrative.scene",
            body = JsonObject(emptyMap()),
            parents = listOf(SourceRef(SourceNodeId("self"))),
        )
        val project = baseProject(source = Source(nodes = listOf(self)))
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        assertTrue(out.data.issues.any { it.code == "source-parent-cycle" })
    }

    @Test fun collectsMultipleIssuesPerClip() = runTest {
        val bad = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 1.seconds),
            sourceRange = TimeRange(Duration.ZERO, 1.seconds),
            assetId = AssetId("missing"),
            volume = 9f,
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Audio(TrackId("at"), listOf(bad))),
                duration = 1.seconds,
            ),
            // asset "missing" is not in project.assets — dangling too
        )
        val rig = newRig(project)
        val out = rig.tool.execute(ValidateProjectTool.Input("p"), rig.ctx)
        val codes = out.data.issues.map { it.code }.toSet()
        assertTrue("dangling-asset" in codes)
        assertTrue("volume-range" in codes)
        assertFalse(out.data.passed)
    }
}
