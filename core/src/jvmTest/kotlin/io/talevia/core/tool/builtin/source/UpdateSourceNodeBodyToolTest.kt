package io.talevia.core.tool.builtin.source

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
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
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
        val store: FileProjectStore,
        val tool: UpdateSourceNodeBodyTool,
        val ctx: ToolContext,
        val pid: ProjectId,
    )

    private suspend fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
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
        store: FileProjectStore,
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
        store: FileProjectStore,
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

    @Test fun flatBodyShapeIsRescuedAtDeserialize() = runTest {
        // Production gpt-5.4-mini kept emitting the flattened form: body fields splat at
        // the top level alongside projectId/nodeId, with no `body` wrapper. Exercise the
        // tool via its own inputSerializer — the same path RegisteredTool.dispatch takes —
        // to prove the transform rebuilds the expected shape before the data class decoder
        // sees it.
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")

        val flatRaw = buildJsonObject {
            put("projectId", kotlinx.serialization.json.JsonPrimitive(rig.pid.value))
            put("nodeId", kotlinx.serialization.json.JsonPrimitive("shot-1"))
            // Deliberately no `body` wrapper — fields at the top level.
            put("framing", kotlinx.serialization.json.JsonPrimitive("close-up"))
            put("dialogue", kotlinx.serialization.json.JsonPrimitive("Where are we?"))
        }

        val decoded = io.talevia.core.JsonConfig.default
            .decodeFromJsonElement(UpdateSourceNodeBodyTool.InputCompatSerializer, flatRaw)
        rig.tool.execute(decoded, rig.ctx)

        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        val body = node.body as kotlinx.serialization.json.JsonObject
        assertEquals("close-up", body["framing"]!!.toString().trim('"'))
        assertEquals("Where are we?", body["dialogue"]!!.toString().trim('"'))
    }

    @Test fun appendsBodyHistoryRevisionOnUpdate() = runTest {
        // source-node-history-query §5.5 — every update_source_node_body call
        // must preserve the pre-edit body as a BodyRevision so
        // source_query(select=history) can surface lost drafts.
        val rig = rig()
        seedShot(
            rig.store,
            rig.pid,
            "shot-1",
            body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
        )

        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("close-up")) },
            ),
            rig.ctx,
        )
        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("medium")) },
            ),
            rig.ctx,
        )

        val history = rig.store.listSourceNodeHistory(rig.pid, SourceNodeId("shot-1"), limit = 10)
        assertEquals(
            2,
            history.size,
            "two updates → two past revisions (pre-edit bodies), not three — seed call didn't run the tool",
        )
        // Newest-first contract: most recently overwritten body appears first.
        val newest = history.first().body as kotlinx.serialization.json.JsonObject
        val oldest = history.last().body as kotlinx.serialization.json.JsonObject
        assertEquals(
            "close-up",
            newest["framing"]!!.toString().trim('"'),
            "newest history entry = body overwritten by the last update",
        )
        assertEquals(
            "wide",
            oldest["framing"]!!.toString().trim('"'),
            "oldest history entry = the original seed body",
        )
    }

    @Test fun noHistoryAppendWhenBodyUnchanged() = runTest {
        // §3a #9 bounded-edge: a redundant update that writes the same body
        // shouldn't pollute history with a no-op revision.
        val rig = rig()
        seedShot(
            rig.store,
            rig.pid,
            "shot-1",
            body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
        )

        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
            ),
            rig.ctx,
        )

        val history = rig.store.listSourceNodeHistory(rig.pid, SourceNodeId("shot-1"), limit = 10)
        assertEquals(
            0,
            history.size,
            "identical-body update must not append a history entry",
        )
    }

    @Test fun restoreFromRevisionIndexRollsBackBody() = runTest {
        // source-node-body-restore-from-history §5.5 happy path. Two
        // updates land two past revisions; restoring index=0 brings the
        // body back to the most-recent historical state (pre-v3), and
        // the pre-restore body enters history as a NEW entry so the
        // audit trail marches forward (no rewriting the past).
        val rig = rig()
        seedShot(
            rig.store,
            rig.pid,
            "shot-1",
            body = buildJsonObject { put("framing", JsonPrimitive("v0-seed")) },
        )

        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("v1-medium")) },
            ),
            rig.ctx,
        )
        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("v2-close-up")) },
            ),
            rig.ctx,
        )
        // At this point: current = v2-close-up; history (newest-first) = [v1-medium, v0-seed].

        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                restoreFromRevisionIndex = 0,
            ),
            rig.ctx,
        )

        // Current body is now v1-medium (index=0 of history).
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        val current = node.body as kotlinx.serialization.json.JsonObject
        assertEquals("v1-medium", current["framing"]!!.toString().trim('"'))

        // History now carries the pre-restore v2-close-up as the new
        // most-recent entry (audit trail marches forward).
        val history = rig.store.listSourceNodeHistory(rig.pid, SourceNodeId("shot-1"), limit = 10)
        val newest = history.first().body as kotlinx.serialization.json.JsonObject
        assertEquals(
            "v2-close-up",
            newest["framing"]!!.toString().trim('"'),
            "pre-restore body must be appended to history (forward arrow of time)",
        )
        assertEquals(
            3,
            history.size,
            "history grows from 2 (v1, v0) → 3 (v2, v1, v0) after the restore adds its pre-restore state",
        )
    }

    @Test fun restoreRejectsBothBodyAndRevisionIndex() = runTest {
        // §3a #9 exactly-one-of edge: supplying both is ambiguous; the tool
        // fails loud rather than silently picking one.
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                UpdateSourceNodeBodyTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-1",
                    body = buildJsonObject { put("framing", JsonPrimitive("boom")) },
                    restoreFromRevisionIndex = 0,
                ),
                rig.ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("exactly one"),
            "error must name the exactly-one-of contract: ${ex.message}",
        )
    }

    @Test fun restoreRejectsNeitherBodyNorRevisionIndex() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                UpdateSourceNodeBodyTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-1",
                    // both null
                ),
                rig.ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("requires either"),
            "error must explain both options: ${ex.message}",
        )
    }

    @Test fun restoreFromEmptyHistoryFailsLoud() = runTest {
        // §3a #9 bounded-edge: a never-updated node has no history entries
        // to restore. Operator gets a clear error + a source_query hint,
        // not a silent no-op.
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                UpdateSourceNodeBodyTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-1",
                    restoreFromRevisionIndex = 0,
                ),
                rig.ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("no body-history entries"),
            "error must surface the empty-history cause: ${ex.message}",
        )
    }

    @Test fun restoreOutOfRangeFailsLoudWithTrueCount() = runTest {
        // §3a #9 bounded-edge: asking for revision 5 when only 2 exist
        // must name the true count, not silently clamp.
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")

        // Populate exactly 2 revisions.
        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("v1")) },
            ),
            rig.ctx,
        )
        rig.tool.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                body = buildJsonObject { put("framing", JsonPrimitive("v2")) },
            ),
            rig.ctx,
        )

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                UpdateSourceNodeBodyTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-1",
                    restoreFromRevisionIndex = 5,
                ),
                rig.ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("out of range"),
            "error must flag out-of-range: ${ex.message}",
        )
        assertTrue(
            "2" in ex.message!!,
            "error must name true history size (2): ${ex.message}",
        )
    }

    @Test fun restoreNegativeIndexFailsLoud() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                UpdateSourceNodeBodyTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-1",
                    restoreFromRevisionIndex = -1,
                ),
                rig.ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("non-negative"),
            "error must name the sign-violation: ${ex.message}",
        )
    }

    @Test fun nestedBodyShapePassesThrough() = runTest {
        // The transform must not interfere with the correct shape either.
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")

        val nestedRaw = buildJsonObject {
            put("projectId", kotlinx.serialization.json.JsonPrimitive(rig.pid.value))
            put("nodeId", kotlinx.serialization.json.JsonPrimitive("shot-1"))
            put(
                "body",
                buildJsonObject { put("framing", kotlinx.serialization.json.JsonPrimitive("wide")) },
            )
        }

        val decoded = io.talevia.core.JsonConfig.default
            .decodeFromJsonElement(UpdateSourceNodeBodyTool.InputCompatSerializer, nestedRaw)
        rig.tool.execute(decoded, rig.ctx)

        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        val body = node.body as kotlinx.serialization.json.JsonObject
        assertEquals("wide", body["framing"]!!.toString().trim('"'))
        assertEquals(1, body.size, "extra keys must not leak in when body is already nested")
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
) {
    put(key, kotlinx.serialization.json.buildJsonArray(block))
}
