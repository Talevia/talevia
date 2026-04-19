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
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ListClipsForSourceToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun fixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")
        // Three clips:
        //  c-1 binds "mei" directly
        //  c-2 binds "scene-1" (which lists mei as a parent) — reaches mei via DAG
        //  c-3 binds nothing (unbound / imported)
        val clipMei = Clip.Video(
            id = ClipId("c-1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-1"),
            sourceBinding = setOf(SourceNodeId("mei")),
        )
        val clipScene = Clip.Video(
            id = ClipId("c-2"),
            timeRange = TimeRange(1.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-2"),
            sourceBinding = setOf(SourceNodeId("scene-1")),
        )
        val clipUnbound = Clip.Video(
            id = ClipId("c-3"),
            timeRange = TimeRange(2.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-3"),
            sourceBinding = emptySet(),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(id = TrackId("v"), clips = listOf(clipMei, clipScene, clipUnbound)),
                    ),
                    duration = 3.seconds,
                ),
            ),
        )
        // Source: mei is a character_ref; scene-1 is a generic node whose parent is mei.
        store.mutateSource(pid) { src ->
            src.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
                .addNode(
                    SourceNode(
                        id = SourceNodeId("scene-1"),
                        kind = "narrative.scene",
                        parents = listOf(SourceRef(SourceNodeId("mei"))),
                    ),
                )
        }
        return store to pid
    }

    @Test fun reportsDirectAndTransitiveBinds() = runTest {
        val (store, pid) = fixture()
        val tool = ListClipsForSourceTool(store)
        val out = tool.execute(
            ListClipsForSourceTool.Input(projectId = pid.value, sourceNodeId = "mei"),
            ctx(),
        ).data
        assertEquals(2, out.clipCount)
        val byClip = out.reports.associateBy { it.clipId }
        assertTrue("c-1" in byClip, "direct bind on c-1 must report")
        assertTrue("c-2" in byClip, "transitive bind on c-2 (via scene-1) must report")
        assertTrue("c-3" !in byClip, "unbound clip must be excluded")
        assertEquals(true, byClip["c-1"]!!.directlyBound)
        assertEquals(false, byClip["c-2"]!!.directlyBound)
        assertEquals(listOf("scene-1"), byClip["c-2"]!!.boundVia)
    }

    @Test fun returnsEmptyReportsForLeafNodeWithoutClips() = runTest {
        val (store, pid) = fixture()
        // Query a node that exists but has no bindings: add an orphan style_bible
        store.mutateSource(pid) { src ->
            src.addNode(SourceNode(id = SourceNodeId("orphan-style"), kind = "core.consistency.style_bible"))
        }
        val tool = ListClipsForSourceTool(store)
        val out = tool.execute(
            ListClipsForSourceTool.Input(projectId = pid.value, sourceNodeId = "orphan-style"),
            ctx(),
        ).data
        assertEquals(0, out.clipCount)
        assertTrue(out.reports.isEmpty())
    }

    @Test fun failsLoudlyOnMissingNode() = runTest {
        val (store, pid) = fixture()
        val tool = ListClipsForSourceTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ListClipsForSourceTool.Input(projectId = pid.value, sourceNodeId = "does-not-exist"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("not found"), "should tell the agent why: ${ex.message}")
    }

    @Test fun echoesTrackIdAndAssetIdPerReport() = runTest {
        val (store, pid) = fixture()
        val tool = ListClipsForSourceTool(store)
        val out = tool.execute(
            ListClipsForSourceTool.Input(projectId = pid.value, sourceNodeId = "mei"),
            ctx(),
        ).data
        val c1 = out.reports.single { it.clipId == "c-1" }
        assertEquals("v", c1.trackId)
        assertEquals("a-1", c1.assetId)
    }

    @Suppress("unused")
    private val unusedSinkForCompiler = JsonObject(emptyMap())
}
