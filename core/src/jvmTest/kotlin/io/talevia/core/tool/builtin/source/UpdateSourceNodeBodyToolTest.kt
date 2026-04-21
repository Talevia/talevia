package io.talevia.core.tool.builtin.source

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
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UpdateSourceNodeBodyToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: UpdateSourceNodeBodyTool,
        val ctx: ToolContext,
        val pid: ProjectId,
    )

    private suspend fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, UpdateSourceNodeBodyTool(store), ctx, pid)
    }

    private suspend fun seedShot(
        store: SqlDelightProjectStore,
        pid: ProjectId,
        nodeId: String,
        body: kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("framing", JsonPrimitive("medium"))
        },
    ) {
        store.mutateSource(pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId(nodeId),
                    kind = "narrative.shot",
                    body = body,
                ),
            )
        }
    }

    private suspend fun seedVideoClip(
        store: SqlDelightProjectStore,
        pid: ProjectId,
        clipId: String,
        trackId: String,
        binding: Set<String>,
    ) {
        store.mutate(pid) { project ->
            val existingTrack = project.timeline.tracks.firstOrNull { it.id.value == trackId } as? Track.Video
            val clip = Clip.Video(
                id = ClipId(clipId),
                timeRange = TimeRange(start = 0.seconds, duration = 2.seconds),
                sourceRange = TimeRange(start = 0.seconds, duration = 2.seconds),
                assetId = AssetId("asset-$clipId"),
                sourceBinding = binding.map { SourceNodeId(it) }.toSet(),
            )
            val nextTrack = (existingTrack ?: Track.Video(id = TrackId(trackId))).copy(
                clips = (existingTrack?.clips.orEmpty()) + clip,
            )
            val nextTracks = if (existingTrack != null) {
                project.timeline.tracks.map { if (it.id.value == trackId) nextTrack else it }
            } else {
                project.timeline.tracks + nextTrack
            }
            project.copy(timeline = project.timeline.copy(tracks = nextTracks))
        }
    }

    @Test fun replacesBodyAndBumpsContentHash() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val before = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.contentHash

        val out = rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject {
                    put("framing", JsonPrimitive("close-up"))
                    put("dialogue", JsonPrimitive("Where are we?"))
                },
            ),
            rig.ctx,
        ).data

        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        assertEquals("close-up", (node.body as kotlinx.serialization.json.JsonObject)["framing"]!!.toString().trim('"'))
        assertNotEquals(before, node.contentHash)
        assertEquals(before, out.previousContentHash)
        assertEquals(node.contentHash, out.newContentHash)
    }

    @Test fun preservesKindAndParents() = runTest {
        val rig = rig()
        // Seed a character parent first, then a shot that references it.
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "v"),
            )
        }
        rig.store.mutateSource(rig.pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId("shot-1"),
                    kind = "narrative.shot",
                    body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
                    parents = listOf(io.talevia.core.domain.source.SourceRef(SourceNodeId("mei"))),
                ),
            )
        }

        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("close")) },
            ),
            rig.ctx,
        )

        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        assertEquals("narrative.shot", node.kind)
        assertEquals(listOf("mei"), node.parents.map { it.nodeId.value })
    }

    @Test fun rejectsMissingNode() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                UpdateSourceNodeBodyTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "nope",
                    body = buildJsonObject { put("k", JsonPrimitive("v")) },
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                UpdateSourceNodeBodyTool.Input(
                    projectId = "no-such-project",
                    nodeId = "whatever",
                    body = buildJsonObject { put("k", JsonPrimitive("v")) },
                ),
                rig.ctx,
            )
        }
    }

    @Test fun worksOnConsistencyKinds() = runTest {
        // Generic body editor must also accept consistency kinds — the typed update_* tools are
        // preferred for partial patch, but the generic whole-replace path is not forbidden.
        val rig = rig()
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "short hair"),
            )
        }
        val before = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash

        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "mei",
                body = buildJsonObject {
                    put("name", JsonPrimitive("Mei"))
                    put("visualDescription", JsonPrimitive("long red hair"))
                    putJsonArray("referenceAssetIds") { }
                    putJsonArray("tags") { }
                },
            ),
            rig.ctx,
        )

        val after = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
        assertNotEquals(before, after)
    }

    @Test fun reportsBoundClipCount() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        seedVideoClip(rig.store, rig.pid, clipId = "c1", trackId = "t1", binding = setOf("shot-1"))
        seedVideoClip(rig.store, rig.pid, clipId = "c2", trackId = "t1", binding = setOf("shot-1"))
        seedVideoClip(rig.store, rig.pid, clipId = "c3", trackId = "t1", binding = emptySet())

        val out = rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
            ),
            rig.ctx,
        ).data

        assertEquals(2, out.boundClipCount)
    }

    @Test fun zeroBoundClipsReportedWhenNoneBind() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val out = rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
            ),
            rig.ctx,
        ).data
        assertEquals(0, out.boundClipCount)
    }

    @Test fun bumpsNodeRevision() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val before = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.revision
        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
            ),
            rig.ctx,
        )
        val after = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.revision
        assertTrue(after > before)
    }

    @Test fun sameBodyReplacementIsIdempotentOnHash() = runTest {
        val rig = rig()
        val body = buildJsonObject { put("framing", JsonPrimitive("medium")) }
        seedShot(rig.store, rig.pid, "shot-1", body = body)
        val before = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.contentHash

        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = body,
            ),
            rig.ctx,
        )
        val after = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.contentHash
        // contentHash is a function of (kind, body, parents); identical body should hash the same.
        assertEquals(before, after)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
) {
    put(key, kotlinx.serialization.json.buildJsonArray(block))
}
