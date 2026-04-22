package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetLockfileEntryPinnedToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
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
        toolId: String = "generate_image",
        assetId: String = inputHash + "-asset",
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = 1L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        pinned = pinned,
    )

    private suspend fun seed(rig: Rig, vararg entries: LockfileEntry) {
        var lockfile = Lockfile.EMPTY
        entries.forEach { lockfile = lockfile.append(it) }
        rig.store.upsert("test", Project(id = ProjectId("p"), timeline = Timeline(), lockfile = lockfile))
    }

    @Test fun pinsEntryAndReportsChange() = runTest {
        val rig = rig()
        seed(rig, entry("h-1"))

        val out = SetLockfileEntryPinnedTool(rig.store).execute(
            SetLockfileEntryPinnedTool.Input(projectId = "p", inputHash = "h-1", pinned = true),
            rig.ctx,
        )

        assertEquals("h-1", out.data.inputHash)
        assertEquals("generate_image", out.data.toolId)
        assertFalse(out.data.pinnedBefore)
        assertTrue(out.data.pinnedAfter)
        assertTrue(out.data.changed)

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.findByInputHash("h-1")!!.pinned)
    }

    @Test fun pinningPinnedEntryIsIdempotent() = runTest {
        val rig = rig()
        seed(rig, entry("h-1", pinned = true))

        val out = SetLockfileEntryPinnedTool(rig.store).execute(
            SetLockfileEntryPinnedTool.Input(projectId = "p", inputHash = "h-1", pinned = true),
            rig.ctx,
        )

        assertTrue(out.data.pinnedBefore)
        assertTrue(out.data.pinnedAfter)
        assertFalse(out.data.changed)
        // Still pinned after the no-op call.
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.findByInputHash("h-1")!!.pinned)
    }

    @Test fun pinningDoesNotTouchOtherEntries() = runTest {
        val rig = rig()
        seed(rig, entry("h-1"), entry("h-2"), entry("h-3"))

        SetLockfileEntryPinnedTool(rig.store).execute(
            SetLockfileEntryPinnedTool.Input(projectId = "p", inputHash = "h-2", pinned = true),
            rig.ctx,
        )

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertFalse(refreshed.lockfile.findByInputHash("h-1")!!.pinned)
        assertTrue(refreshed.lockfile.findByInputHash("h-2")!!.pinned)
        assertFalse(refreshed.lockfile.findByInputHash("h-3")!!.pinned)
    }

    @Test fun missingHashFailsLoudly() = runTest {
        val rig = rig()
        seed(rig, entry("h-1"))

        val ex = assertFailsWith<IllegalStateException> {
            SetLockfileEntryPinnedTool(rig.store).execute(
                SetLockfileEntryPinnedTool.Input(projectId = "p", inputHash = "ghost", pinned = true),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        // Error message nudges toward list_lockfile_entries.
        assertTrue(ex.message!!.contains("list_lockfile_entries"), ex.message)
    }

    @Test fun missingProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SetLockfileEntryPinnedTool(rig.store).execute(
                SetLockfileEntryPinnedTool.Input(projectId = "ghost", inputHash = "h-1", pinned = true),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun unpinClearsPin() = runTest {
        val rig = rig()
        seed(rig, entry("h-1", pinned = true))

        val out = SetLockfileEntryPinnedTool(rig.store).execute(
            SetLockfileEntryPinnedTool.Input(projectId = "p", inputHash = "h-1", pinned = false),
            rig.ctx,
        )

        assertTrue(out.data.pinnedBefore)
        assertFalse(out.data.pinnedAfter)
        assertTrue(out.data.changed)
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertFalse(refreshed.lockfile.findByInputHash("h-1")!!.pinned)
    }

    @Test fun unpinOnAlreadyUnpinnedIsIdempotent() = runTest {
        val rig = rig()
        seed(rig, entry("h-1", pinned = false))

        val out = SetLockfileEntryPinnedTool(rig.store).execute(
            SetLockfileEntryPinnedTool.Input(projectId = "p", inputHash = "h-1", pinned = false),
            rig.ctx,
        )

        assertFalse(out.data.pinnedBefore)
        assertFalse(out.data.pinnedAfter)
        assertFalse(out.data.changed)
    }

    @Test fun unpinMissingHashFailsLoudly() = runTest {
        val rig = rig()
        seed(rig, entry("h-1"))
        val ex = assertFailsWith<IllegalStateException> {
            SetLockfileEntryPinnedTool(rig.store).execute(
                SetLockfileEntryPinnedTool.Input(projectId = "p", inputHash = "ghost", pinned = false),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }
}
