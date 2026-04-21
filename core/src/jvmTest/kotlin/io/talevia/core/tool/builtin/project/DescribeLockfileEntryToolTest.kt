package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
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
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DescribeLockfileEntryToolTest {

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

    private fun entry(
        inputHash: String,
        assetId: String,
        pinned: Boolean = false,
        sourceBinding: Set<SourceNodeId> = emptySet(),
        sourceContentHashes: Map<SourceNodeId, String> = emptyMap(),
        baseInputs: JsonObject = JsonObject(emptyMap()),
    ): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = "v1",
            seed = 42L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        sourceBinding = sourceBinding,
        sourceContentHashes = sourceContentHashes,
        baseInputs = baseInputs,
        pinned = pinned,
    )

    @Test fun describesFullEntryForLivePinnedRow() = runTest {
        val rig = rig()
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c-1"),
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId("a-hero"),
                                ),
                            ),
                        ),
                    ),
                    duration = 2.seconds,
                ),
                lockfile = Lockfile.EMPTY.append(
                    entry(
                        inputHash = "h-hero",
                        assetId = "a-hero",
                        pinned = true,
                        sourceBinding = setOf(SourceNodeId("mei")),
                        baseInputs = buildJsonObject { put("prompt", JsonPrimitive("Mei portrait")) },
                    ),
                ),
            ),
        )
        rig.store.mutateSource(ProjectId("p")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }

        val out = DescribeLockfileEntryTool(rig.store).execute(
            DescribeLockfileEntryTool.Input(projectId = "p", inputHash = "h-hero"),
            rig.ctx,
        ).data

        assertEquals("h-hero", out.inputHash)
        assertEquals("generate_image", out.toolId)
        assertEquals("a-hero", out.assetId)
        assertTrue(out.pinned)
        assertEquals(listOf("mei"), out.sourceBindingIds)
        assertEquals("fake", out.provenance.providerId)
        assertEquals(42L, out.provenance.seed)
        assertEquals("v1", out.provenance.modelVersion)

        val baseInputs = out.baseInputs
        assertEquals("Mei portrait", (baseInputs["prompt"] as JsonPrimitive).content)
        assertFalse(out.baseInputsEmpty)

        assertEquals(1, out.clipReferences.size)
        assertEquals("c-1", out.clipReferences.single().clipId)
        assertEquals("video", out.clipReferences.single().clipType)
    }

    @Test fun detectsDriftWhenSourceChanged() = runTest {
        val rig = rig()
        rig.store.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(ProjectId("p")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val snapshotHash = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("mei")]!!.contentHash
        rig.store.mutate(ProjectId("p")) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    entry(
                        inputHash = "h-1",
                        assetId = "a-1",
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to snapshotHash),
                    ),
                ),
            )
        }
        // Edit Mei — her contentHash will drift.
        rig.store.mutateSource(ProjectId("p")) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red"),
                    ),
                )
            }
        }

        val out = DescribeLockfileEntryTool(rig.store).execute(
            DescribeLockfileEntryTool.Input(projectId = "p", inputHash = "h-1"),
            rig.ctx,
        ).data

        assertTrue(out.currentlyStale, "entry must be marked stale after mei drift")
        val drifted = out.driftedNodes.single()
        assertEquals("mei", drifted.nodeId)
        assertEquals(snapshotHash, drifted.snapshotContentHash)
        assertTrue(drifted.currentContentHash != null && drifted.currentContentHash != snapshotHash)
    }

    @Test fun freshEntryWithMatchingHashesIsNotStale() = runTest {
        val rig = rig()
        rig.store.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(ProjectId("p")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val hash = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("mei")]!!.contentHash
        rig.store.mutate(ProjectId("p")) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    entry(
                        inputHash = "h-fresh",
                        assetId = "a-fresh",
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to hash),
                    ),
                ),
            )
        }

        val out = DescribeLockfileEntryTool(rig.store).execute(
            DescribeLockfileEntryTool.Input(projectId = "p", inputHash = "h-fresh"),
            rig.ctx,
        ).data

        assertFalse(out.currentlyStale)
        assertTrue(out.driftedNodes.isEmpty())
    }

    @Test fun orphanEntryReportsNoClipRefs() = runTest {
        val rig = rig()
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(),
                lockfile = Lockfile.EMPTY.append(entry("h-orphan", "a-orphan")),
            ),
        )
        val out = DescribeLockfileEntryTool(rig.store).execute(
            DescribeLockfileEntryTool.Input(projectId = "p", inputHash = "h-orphan"),
            rig.ctx,
        ).data
        assertTrue(out.clipReferences.isEmpty())
    }

    @Test fun legacyEntryReportsBaseInputsEmpty() = runTest {
        val rig = rig()
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(),
                lockfile = Lockfile.EMPTY.append(entry("h-legacy", "a-legacy")),
            ),
        )
        val out = DescribeLockfileEntryTool(rig.store).execute(
            DescribeLockfileEntryTool.Input(projectId = "p", inputHash = "h-legacy"),
            rig.ctx,
        ).data
        assertTrue(out.baseInputsEmpty)
        assertEquals(JsonObject(emptyMap()), out.baseInputs)
    }

    @Test fun missingHashFailsLoud() = runTest {
        val rig = rig()
        rig.store.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            DescribeLockfileEntryTool(rig.store).execute(
                DescribeLockfileEntryTool.Input(projectId = "p", inputHash = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("project_query(select=lockfile_entries)"), ex.message)
    }
}
