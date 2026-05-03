package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.LockfileEntryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the new `sinceEpochMs` and per-`sourceNodeId` filters on
 * `project_query(select=lockfile_entries)` — the lockfile-history-explorer
 * direction: "show me this character's generation history" and "what has
 * this project burned since {timestamp}".
 */
class ProjectQueryLockfileFiltersTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun entry(
        hash: String,
        tool: String,
        createdAtEpochMs: Long,
        bindings: Set<String> = emptySet(),
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = tool,
        assetId = AssetId("asset-$hash"),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAtEpochMs,
        ),
        sourceBinding = bindings.map { SourceNodeId(it) }.toSet(),
    )

    private suspend fun fixture(entries: List<LockfileEntry>): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList()),
                assets = emptyList(),
                lockfile = EagerLockfile(entries = entries),
            ),
        )
        return store to pid
    }

    @Test fun sinceEpochMsFiltersOlderEntries() = runTest {
        val (store, pid) = fixture(
            listOf(
                entry("old-1", "generate_image", createdAtEpochMs = 1_000),
                entry("mid-1", "generate_image", createdAtEpochMs = 2_000),
                entry("new-1", "generate_image", createdAtEpochMs = 3_000),
            ),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "lockfile_entries",
                sinceEpochMs = 2_000,
            ),
            ctx(),
        ).data
        assertEquals(2, out.total)
        val rows = out.rows.decodeRowsAs(LockfileEntryRow.serializer())
        assertEquals(setOf("new-1", "mid-1"), rows.map { it.inputHash }.toSet())
    }

    @Test fun sourceNodeIdFiltersEntriesBoundToThatNode() = runTest {
        val (store, pid) = fixture(
            listOf(
                entry("h1", "generate_image", 1_000, bindings = setOf("mei")),
                entry("h2", "generate_image", 1_100, bindings = setOf("mei", "style-bible")),
                entry("h3", "generate_image", 1_200, bindings = setOf("other-char")),
                entry("h4", "synthesize_speech", 1_300, bindings = emptySet()),
            ),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "lockfile_entries",
                sourceNodeId = "mei",
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(LockfileEntryRow.serializer())
        assertEquals(setOf("h2", "h1"), rows.map { it.inputHash }.toSet())
        assertTrue(rows.all { "mei" in it.sourceBindingIds })
    }

    @Test fun sourceNodeIdAndSinceCombine() = runTest {
        val (store, pid) = fixture(
            listOf(
                entry("old-mei", "generate_image", 500, bindings = setOf("mei")),
                entry("new-mei", "generate_image", 2_500, bindings = setOf("mei")),
                entry("new-other", "generate_image", 2_600, bindings = setOf("other")),
            ),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "lockfile_entries",
                sourceNodeId = "mei",
                sinceEpochMs = 1_000,
            ),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(LockfileEntryRow.serializer())
        assertEquals(listOf("new-mei"), rows.map { it.inputHash })
    }

    @Test fun sinceOnSelectOtherThanLockfileEntriesFailsLoud() = runTest {
        val (store, pid) = fixture(emptyList())
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "tracks",
                    sinceEpochMs = 0,
                ),
                ctx(),
            )
        }
    }

    @Test fun sourceNodeIdNotMatchingAnyBindingYieldsEmpty() = runTest {
        val (store, pid) = fixture(
            listOf(entry("h1", "generate_image", 1_000, bindings = setOf("real-char"))),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "lockfile_entries",
                sourceNodeId = "phantom-char",
            ),
            ctx(),
        ).data
        assertEquals(0, out.total)
    }

    @Test fun existingToolIdFilterStillWorks() = runTest {
        val (store, pid) = fixture(
            listOf(
                entry("h1", "generate_image", 1_000),
                entry("h2", "synthesize_speech", 1_100),
            ),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "lockfile_entries",
                toolId = "synthesize_speech",
            ),
            ctx(),
        ).data
        assertEquals(1, out.total)
    }
}
