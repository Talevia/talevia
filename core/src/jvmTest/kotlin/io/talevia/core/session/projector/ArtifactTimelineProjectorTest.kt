package io.talevia.core.session.projector

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactTimelineProjectorTest {

    private data class Rig(
        val sessions: SqlDelightSessionStore,
        val projects: FileProjectStore,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        return Rig(
            sessions = SqlDelightSessionStore(db, EventBus()),
            projects = ProjectStoreTestKit.create(),
        )
    }

    private fun entry(
        inputHash: String,
        toolId: String = "generate_image",
        assetId: String = "$inputHash-asset",
        createdAtEpochMs: Long = 1_700_000_000_000L,
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "anthropic",
            modelId = "claude-opus-4-7",
            modelVersion = null,
            seed = 42L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAtEpochMs,
        ),
        pinned = pinned,
    )

    private suspend fun seedSessionAndProject(
        rig: Rig,
        sessionId: String = "s1",
        projectId: String = "p1",
        currentProjectId: String? = projectId,
        entries: List<LockfileEntry> = emptyList(),
    ) {
        var lockfile = Lockfile.EMPTY
        entries.forEach { lockfile = lockfile.append(it) }
        rig.projects.upsert(
            "demo",
            Project(
                id = ProjectId(projectId),
                timeline = Timeline(),
                lockfile = lockfile,
            ),
        )
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        rig.sessions.createSession(
            Session(
                id = SessionId(sessionId),
                projectId = ProjectId(projectId),
                title = sessionId,
                createdAt = now,
                updatedAt = now,
                currentProjectId = currentProjectId?.let { ProjectId(it) },
            ),
        )
    }

    @Test fun unboundSessionReturnsEmptyTimeline() = runTest {
        val rig = rig()
        seedSessionAndProject(rig, currentProjectId = null)
        val out = ArtifactTimelineProjector(rig.sessions, rig.projects).project(SessionId("s1"))
        assertEquals("s1", out.sessionId)
        assertNull(out.projectId)
        assertTrue(out.entries.isEmpty())
    }

    @Test fun boundProjectWithNoEntriesReturnsEmpty() = runTest {
        val rig = rig()
        seedSessionAndProject(rig)
        val out = ArtifactTimelineProjector(rig.sessions, rig.projects).project(SessionId("s1"))
        assertEquals("p1", out.projectId)
        assertTrue(out.entries.isEmpty())
    }

    @Test fun entriesAreOrderedMostRecentFirst() = runTest {
        val rig = rig()
        seedSessionAndProject(
            rig,
            entries = listOf(
                entry("hash-a", createdAtEpochMs = 1_700_000_100_000L),
                entry("hash-b", createdAtEpochMs = 1_700_000_300_000L),
                entry("hash-c", createdAtEpochMs = 1_700_000_200_000L),
            ),
        )
        val out = ArtifactTimelineProjector(rig.sessions, rig.projects).project(SessionId("s1"))
        assertEquals(listOf("hash-b", "hash-c", "hash-a"), out.entries.map { it.inputHash })
    }

    @Test fun pinnedFlagAndProvenanceFieldsAreCarriedThrough() = runTest {
        val rig = rig()
        seedSessionAndProject(
            rig,
            entries = listOf(entry("hash-a", toolId = "generate_music", pinned = true)),
        )
        val out = ArtifactTimelineProjector(rig.sessions, rig.projects).project(SessionId("s1"))
        val row = out.entries.single()
        assertEquals("hash-a", row.inputHash)
        assertEquals("generate_music", row.toolId)
        assertEquals("anthropic", row.providerId)
        assertEquals("claude-opus-4-7", row.modelId)
        assertEquals(42L, row.seed)
        assertEquals(true, row.pinned)
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = kotlin.runCatching {
            ArtifactTimelineProjector(rig.sessions, rig.projects).project(SessionId("ghost"))
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }
}
