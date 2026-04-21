package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListLockfileEntriesToolTest {

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
        toolId: String,
        assetId: String,
        modelId: String = "fake-model",
        seed: Long = 1L,
        createdAt: Long = 1_700_000_000_000L,
        bindings: Set<SourceNodeId> = emptySet(),
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = "h-$assetId",
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = modelId,
            modelVersion = null,
            seed = seed,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAt,
        ),
        sourceBinding = bindings,
        pinned = pinned,
    )

    private suspend fun seed(rig: Rig, vararg entries: LockfileEntry) {
        var lockfile = Lockfile.EMPTY
        entries.forEach { lockfile = lockfile.append(it) }
        rig.store.upsert(
            "test",
            Project(id = ProjectId("p"), timeline = Timeline(), lockfile = lockfile),
        )
    }

    @Test fun emptyLockfileReturnsEmpty() = runTest {
        val rig = rig()
        rig.store.upsert("test", Project(id = ProjectId("p"), timeline = Timeline()))
        val out = ListLockfileEntriesTool(rig.store).execute(
            ListLockfileEntriesTool.Input(projectId = "p"),
            rig.ctx,
        )
        assertEquals(0, out.data.totalEntries)
        assertEquals(0, out.data.returnedEntries)
        assertTrue(out.data.entries.isEmpty())
    }

    @Test fun returnsMostRecentFirstAndPreservesProvenance() = runTest {
        val rig = rig()
        seed(
            rig,
            entry("generate_image", "a-old", createdAt = 1_000L, seed = 7L, bindings = setOf(SourceNodeId("mei"))),
            entry("synthesize_speech", "a-mid", createdAt = 2_000L, modelId = "tts-1", seed = 8L),
            entry("generate_image", "a-new", createdAt = 3_000L, seed = 9L),
        )
        val out = ListLockfileEntriesTool(rig.store).execute(
            ListLockfileEntriesTool.Input(projectId = "p"),
            rig.ctx,
        )

        assertEquals(3, out.data.totalEntries)
        assertEquals(3, out.data.returnedEntries)
        assertEquals(listOf("a-new", "a-mid", "a-old"), out.data.entries.map { it.assetId })
        // Provenance round-tripped.
        val first = out.data.entries.first { it.assetId == "a-old" }
        assertEquals(7L, first.seed)
        assertEquals(listOf("mei"), first.sourceBindingIds)
    }

    @Test fun toolIdFilterScopesToModality() = runTest {
        val rig = rig()
        seed(
            rig,
            entry("generate_image", "img-1"),
            entry("synthesize_speech", "tts-1"),
            entry("generate_image", "img-2"),
        )
        val out = ListLockfileEntriesTool(rig.store).execute(
            ListLockfileEntriesTool.Input(projectId = "p", toolId = "generate_image"),
            rig.ctx,
        )
        assertEquals(2, out.data.totalEntries)
        assertEquals(setOf("img-1", "img-2"), out.data.entries.map { it.assetId }.toSet())
    }

    @Test fun limitTakesFromTheRecentTail() = runTest {
        val rig = rig()
        seed(
            rig,
            entry("generate_image", "a-1", createdAt = 1L),
            entry("generate_image", "a-2", createdAt = 2L),
            entry("generate_image", "a-3", createdAt = 3L),
            entry("generate_image", "a-4", createdAt = 4L),
            entry("generate_image", "a-5", createdAt = 5L),
        )
        val out = ListLockfileEntriesTool(rig.store).execute(
            ListLockfileEntriesTool.Input(projectId = "p", limit = 2),
            rig.ctx,
        )
        assertEquals(5, out.data.totalEntries)
        assertEquals(2, out.data.returnedEntries)
        assertEquals(listOf("a-5", "a-4"), out.data.entries.map { it.assetId })
    }

    @Test fun limitClampedToMax() = runTest {
        val rig = rig()
        seed(rig, entry("generate_image", "a-1"))
        val out = ListLockfileEntriesTool(rig.store).execute(
            ListLockfileEntriesTool.Input(projectId = "p", limit = 999_999),
            rig.ctx,
        )
        // No exception — clamped silently. Single entry returned.
        assertEquals(1, out.data.returnedEntries)
    }

    @Test fun missingProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ListLockfileEntriesTool(rig.store).execute(
                ListLockfileEntriesTool.Input(projectId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun pinnedFlagIsSurfaced() = runTest {
        val rig = rig()
        seed(
            rig,
            entry("generate_image", "hero", pinned = true),
            entry("generate_image", "other", pinned = false),
        )
        val out = ListLockfileEntriesTool(rig.store).execute(
            ListLockfileEntriesTool.Input(projectId = "p"),
            rig.ctx,
        )
        val byAsset = out.data.entries.associateBy { it.assetId }
        assertTrue(byAsset.getValue("hero").pinned)
        assertTrue(!byAsset.getValue("other").pinned)
    }
}
