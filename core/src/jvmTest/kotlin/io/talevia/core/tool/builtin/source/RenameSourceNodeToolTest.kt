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
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RenameSourceNodeToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: RenameSourceNodeTool,
        val ctx: ToolContext,
        val pid: ProjectId,
        val emitted: MutableList<Part>,
    )

    private suspend fun rig(project: Project = Project(id = ProjectId("p"), timeline = Timeline())): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        store.upsert("demo", project)
        val emitted = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { emitted += it },
            messages = emptyList(),
        )
        return Rig(store, RenameSourceNodeTool(store), ctx, project.id, emitted)
    }

    private suspend fun seedShot(
        store: SqlDelightProjectStore,
        pid: ProjectId,
        nodeId: String,
        parents: List<String> = emptyList(),
    ) {
        store.mutateSource(pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId(nodeId),
                    kind = "narrative.shot",
                    body = buildJsonObject { put("framing", JsonPrimitive("medium")) },
                    parents = parents.map { SourceRef(SourceNodeId(it)) },
                ),
            )
        }
    }

    // 1. Happy path: rename a standalone node, verify node id changed and no side
    // effects (no other parents, no clips, no lockfile entries touched).
    @Test fun renamesStandaloneNode() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("character-mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val hashBefore = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!.contentHash

        val out = rig.tool.execute(
            RenameSourceNodeTool.Input(
                projectId = rig.pid.value,
                oldId = "character-mei",
                newId = "character-mei-v2",
            ),
            rig.ctx,
        ).data

        assertEquals("character-mei", out.oldId)
        assertEquals("character-mei-v2", out.newId)
        assertEquals(0, out.parentsRewrittenCount)
        assertEquals(0, out.clipsRewrittenCount)
        assertEquals(0, out.lockfileEntriesRewrittenCount)

        val after = rig.store.get(rig.pid)!!.source
        assertNull(after.byId[SourceNodeId("character-mei")])
        val renamed = after.byId[SourceNodeId("character-mei-v2")]
        assertNotNull(renamed)
        // contentHash is over (kind, body, parents) — none of those changed for the
        // renamed node itself, so the numeric value must survive the rename.
        assertEquals(hashBefore, renamed.contentHash)
    }

    // 2. Rename updates SourceNode.parents on descendant nodes, and the descendants'
    // contentHash cascades (because the serialised parent ref changed).
    @Test fun rewritesParentRefsOnDescendants() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "warm", description = "cozy"),
            )
        }
        seedShot(rig.store, rig.pid, "shot-1", parents = listOf("style-warm"))
        seedShot(rig.store, rig.pid, "shot-2", parents = listOf("style-warm"))
        seedShot(rig.store, rig.pid, "shot-unrelated")
        val shot1HashBefore = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.contentHash
        val shotUnrelatedHashBefore =
            rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-unrelated")]!!.contentHash

        val out = rig.tool.execute(
            RenameSourceNodeTool.Input(
                projectId = rig.pid.value,
                oldId = "style-warm",
                newId = "style-cozy",
            ),
            rig.ctx,
        ).data

        assertEquals(2, out.parentsRewrittenCount) // shot-1 and shot-2

        val after = rig.store.get(rig.pid)!!.source
        assertEquals(
            listOf("style-cozy"),
            after.byId[SourceNodeId("shot-1")]!!.parents.map { it.nodeId.value },
        )
        assertEquals(
            listOf("style-cozy"),
            after.byId[SourceNodeId("shot-2")]!!.parents.map { it.nodeId.value },
        )
        // shot-unrelated's parents list was untouched → hash stable.
        assertEquals(
            shotUnrelatedHashBefore,
            after.byId[SourceNodeId("shot-unrelated")]!!.contentHash,
        )
        // shot-1's parent ref changed → hash bumps (correct stale propagation).
        assertTrue(after.byId[SourceNodeId("shot-1")]!!.contentHash != shot1HashBefore)
    }

    // 3. Rename updates Clip.sourceBinding on bound clips across multiple tracks
    // and multiple clip kinds (Video / Audio / Text).
    @Test fun rewritesClipSourceBindings() = runTest {
        val pid = ProjectId("p")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("vt"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("asset-c1"),
                            sourceBinding = setOf(SourceNodeId("mei"), SourceNodeId("style-warm")),
                        ),
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(5.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("asset-c2"),
                            sourceBinding = setOf(SourceNodeId("style-warm")),
                        ),
                    ),
                ),
                Track.Audio(
                    id = TrackId("at"),
                    clips = listOf(
                        Clip.Audio(
                            id = ClipId("a1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("asset-a1"),
                            sourceBinding = setOf(SourceNodeId("mei")),
                        ),
                    ),
                ),
                Track.Subtitle(
                    id = TrackId("st"),
                    clips = listOf(
                        Clip.Text(
                            id = ClipId("t1"),
                            timeRange = TimeRange(0.seconds, 3.seconds),
                            text = "hello",
                            sourceBinding = setOf(SourceNodeId("mei")),
                        ),
                    ),
                ),
            ),
            duration = 10.seconds,
        )
        val rig = rig(Project(id = pid, timeline = timeline))
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        rig.store.mutateSource(rig.pid) {
            it.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "warm", description = "cozy"),
            )
        }

        val out = rig.tool.execute(
            RenameSourceNodeTool.Input(
                projectId = rig.pid.value,
                oldId = "mei",
                newId = "mei-prime",
            ),
            rig.ctx,
        ).data
        assertEquals(3, out.clipsRewrittenCount) // c1 + a1 + t1

        val after = rig.store.get(rig.pid)!!.timeline
        val allClips = after.tracks.flatMap { it.clips }
        for (clip in allClips) {
            assertFalse(
                SourceNodeId("mei") in clip.sourceBinding,
                "clip ${clip.id.value} still holds old id",
            )
        }
        assertTrue(SourceNodeId("mei-prime") in allClips.first { it.id == ClipId("c1") }.sourceBinding)
        assertTrue(SourceNodeId("style-warm") in allClips.first { it.id == ClipId("c1") }.sourceBinding)
        assertTrue(SourceNodeId("mei-prime") in allClips.first { it.id == ClipId("a1") }.sourceBinding)
        assertTrue(SourceNodeId("mei-prime") in allClips.first { it.id == ClipId("t1") }.sourceBinding)
        // c2 was not bound to mei → untouched.
        assertEquals(
            setOf(SourceNodeId("style-warm")),
            allClips.first { it.id == ClipId("c2") }.sourceBinding,
        )

        // Timeline snapshot was emitted so revert_timeline can roll the rename back.
        assertEquals(1, rig.emitted.count { it is Part.TimelineSnapshot })
    }

    // 4. Rename rewrites LockfileEntry.sourceBinding + sourceContentHashes key.
    @Test fun rewritesLockfileBindingsAndHashKeys() = runTest {
        val pid = ProjectId("p")
        val entry = LockfileEntry(
            inputHash = "hash-1",
            toolId = "generate_image",
            assetId = AssetId("asset-c1"),
            provenance = GenerationProvenance(
                providerId = "openai",
                modelId = "gpt-image-1",
                modelVersion = null,
                seed = 42L,
                parameters = JsonObject(emptyMap()),
                createdAtEpochMs = 0L,
            ),
            sourceBinding = setOf(SourceNodeId("mei"), SourceNodeId("style-warm")),
            sourceContentHashes = mapOf(
                SourceNodeId("mei") to "mei-hash",
                SourceNodeId("style-warm") to "style-hash",
            ),
        )
        val unrelatedEntry = entry.copy(
            inputHash = "hash-2",
            assetId = AssetId("asset-c2"),
            sourceBinding = setOf(SourceNodeId("style-warm")),
            sourceContentHashes = mapOf(SourceNodeId("style-warm") to "style-hash"),
        )
        val project = Project(
            id = pid,
            timeline = Timeline(),
            lockfile = Lockfile(entries = listOf(entry, unrelatedEntry)),
        )
        val rig = rig(project)
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }

        val out = rig.tool.execute(
            RenameSourceNodeTool.Input(
                projectId = rig.pid.value,
                oldId = "mei",
                newId = "mei-prime",
            ),
            rig.ctx,
        ).data
        assertEquals(1, out.lockfileEntriesRewrittenCount)

        val after = rig.store.get(rig.pid)!!.lockfile
        val touched = after.entries.first { it.inputHash == "hash-1" }
        assertFalse(SourceNodeId("mei") in touched.sourceBinding)
        assertTrue(SourceNodeId("mei-prime") in touched.sourceBinding)
        assertTrue(SourceNodeId("style-warm") in touched.sourceBinding)
        // hash map key rewritten, value preserved.
        assertNull(touched.sourceContentHashes[SourceNodeId("mei")])
        assertEquals("mei-hash", touched.sourceContentHashes[SourceNodeId("mei-prime")])
        assertEquals("style-hash", touched.sourceContentHashes[SourceNodeId("style-warm")])
        // Unrelated entry is untouched (still same map, same set).
        val untouched = after.entries.first { it.inputHash == "hash-2" }
        assertEquals(setOf(SourceNodeId("style-warm")), untouched.sourceBinding)
    }

    // 5. Same-id no-op returns without mutation.
    @Test fun sameIdIsNoOp() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val before = rig.store.get(rig.pid)!!

        val out = rig.tool.execute(
            RenameSourceNodeTool.Input(
                projectId = rig.pid.value,
                oldId = "mei",
                newId = "mei",
            ),
            rig.ctx,
        ).data
        assertEquals(0, out.parentsRewrittenCount)
        assertEquals(0, out.clipsRewrittenCount)
        assertEquals(0, out.lockfileEntriesRewrittenCount)

        val after = rig.store.get(rig.pid)!!
        // Whole-project equality — confirms no revision bump, no mutation.
        assertEquals(before, after)
        // And no snapshot emitted.
        assertEquals(0, rig.emitted.count { it is Part.TimelineSnapshot })
    }

    // 6. Rejects on oldId not found, and leaves project untouched.
    @Test fun rejectsUnknownOldId() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val before = rig.store.get(rig.pid)!!

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                RenameSourceNodeTool.Input(
                    projectId = rig.pid.value,
                    oldId = "does-not-exist",
                    newId = "fresh",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"), ex.message)

        val after = rig.store.get(rig.pid)!!
        assertEquals(before, after)
    }

    // 7. Rejects on newId collision with an existing node, and leaves state untouched.
    @Test fun rejectsNewIdCollision() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("kai"),
                CharacterRefBody(name = "Kai", visualDescription = "silver hair"),
            )
        }
        val before = rig.store.get(rig.pid)!!

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                RenameSourceNodeTool.Input(
                    projectId = rig.pid.value,
                    oldId = "mei",
                    newId = "kai",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("already exists"), ex.message)

        val after = rig.store.get(rig.pid)!!
        assertEquals(before, after)
    }

    // 8. Rejects on malformed newId (invalid slug), state untouched.
    @Test fun rejectsMalformedNewId() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val before = rig.store.get(rig.pid)!!

        for (bad in listOf("", " ", "Has Spaces", "has/slash", "UPPER", "-leading", "trailing-")) {
            val ex = assertFailsWith<IllegalArgumentException> {
                rig.tool.execute(
                    RenameSourceNodeTool.Input(
                        projectId = rig.pid.value,
                        oldId = "mei",
                        newId = bad,
                    ),
                    rig.ctx,
                )
            }
            assertTrue(
                ex.message!!.contains("not a valid source-node id slug"),
                "expected slug-validation error for '$bad', got: ${ex.message}",
            )
        }

        val after = rig.store.get(rig.pid)!!
        assertEquals(before, after)
    }

    // 9. A rename that touches Clip.sourceBinding emits exactly one TimelineSnapshot;
    // a rename that doesn't touch any clip emits zero (avoids noisy undo stack).
    @Test fun emitsTimelineSnapshotOnlyWhenClipsAffected() = runTest {
        // Case A: clip-touching rename → one snapshot.
        val pid = ProjectId("p1")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("vt"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("a1"),
                            sourceBinding = setOf(SourceNodeId("mei")),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        val rigWithClip = rig(Project(id = pid, timeline = timeline))
        rigWithClip.store.mutateSource(rigWithClip.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal"),
            )
        }
        rigWithClip.tool.execute(
            RenameSourceNodeTool.Input(
                projectId = rigWithClip.pid.value,
                oldId = "mei",
                newId = "mei-prime",
            ),
            rigWithClip.ctx,
        )
        assertEquals(1, rigWithClip.emitted.count { it is Part.TimelineSnapshot })

        // Case B: no clip binds the node → zero snapshots.
        val rigNoClip = rig()
        rigNoClip.store.mutateSource(rigNoClip.pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal"),
            )
        }
        rigNoClip.tool.execute(
            RenameSourceNodeTool.Input(
                projectId = rigNoClip.pid.value,
                oldId = "mei",
                newId = "mei-prime",
            ),
            rigNoClip.ctx,
        )
        assertEquals(0, rigNoClip.emitted.count { it is Part.TimelineSnapshot })
    }
}
