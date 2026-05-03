package io.talevia.core.session.projector

import io.talevia.core.AssetId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [ArtifactTimelineProjector] —
 * `core/session/projector/ArtifactTimeline.kt`. The
 * "what have I made so far?" UI projector that walks a
 * session's bound project's lockfile entries into a
 * descending-time-ordered audit shape. Cycle 152 audit:
 * 78 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Session not found → throws.** The session id is the
 *    primary key for projection — caller specified a session
 *    that doesn't exist, so loud failure is the right move
 *    (UI dispatch should never get a non-existent session id
 *    here). Diagnostic includes the offending sessionId.
 *
 * 2. **Session unbound OR project missing → empty UI shape,
 *    NOT throw.** Per kdoc: "vs erroring — UIs would rather
 *    show an empty state than explode at render time." Both
 *    null-projectId (session exists, hasn't picked a project)
 *    and missing-project (currentProjectId set but
 *    `projects.get` returns null) return ArtifactTimeline
 *    with empty entries — semantically distinct because
 *    `projectId` differs (null vs the dangling id).
 *
 * 3. **Entries sort descending by `provenance.createdAtEpochMs`
 *    (most-recent first).** Per kdoc: "matches the
 *    `session_query(select=parts)` / list_lockfile_entries
 *    default ordering." Drift to ascending would surface
 *    stale early generations at the top of the UI scroll
 *    while burying recent work.
 */
class ArtifactTimelineTest {

    private val now = Clock.System.now()

    /** Minimal SessionStore — only `getSession(id)` is exercised. */
    private class FakeSessionStore(
        private val sessions: Map<SessionId, Session> = emptyMap(),
    ) : SessionStore {
        override suspend fun createSession(session: Session) = error("not used")
        override suspend fun updateSession(session: Session) = error("not used")
        override suspend fun getSession(id: SessionId): Session? = sessions[id]
        override suspend fun deleteSession(id: SessionId) = error("not used")
        override suspend fun listSessions(projectId: ProjectId?): List<Session> = emptyList()
        override suspend fun listSessionsIncludingArchived(projectId: ProjectId?): List<Session> = emptyList()
        override suspend fun listChildSessions(parentId: SessionId): List<Session> = emptyList()
        override suspend fun appendMessage(message: Message) = error("not used")
        override suspend fun updateMessage(message: Message) = error("not used")
        override suspend fun getMessage(id: MessageId): Message? = null
        override suspend fun listMessages(sessionId: SessionId): List<Message> = emptyList()
        override suspend fun deleteMessage(id: MessageId) = error("not used")
        override suspend fun deleteMessagesAfter(sessionId: SessionId, anchorMessageId: MessageId): Int = 0
        override suspend fun upsertPart(part: Part) = error("not used")
        override suspend fun markPartCompacted(id: PartId, at: Instant) = error("not used")
        override suspend fun getPart(id: PartId): Part? = null
        override suspend fun listParts(messageId: MessageId): List<Part> = emptyList()
        override suspend fun listSessionParts(sessionId: SessionId, includeCompacted: Boolean): List<Part> = emptyList()
        override suspend fun listMessagesWithParts(
            sessionId: SessionId,
            includeCompacted: Boolean,
        ): List<MessageWithParts> = emptyList()
        override suspend fun searchTextInParts(
            query: String,
            sessionId: SessionId?,
            limit: Int,
            offset: Int,
        ): List<Part.Text> = emptyList()
        override suspend fun fork(parentId: SessionId, newTitle: String?, anchorMessageId: MessageId?): SessionId =
            error("not used")
        override fun observeSessionParts(sessionId: SessionId): Flow<Part> = emptyFlow()
    }

    /** Minimal ProjectStore — only `get(id)` is exercised. */
    private class FakeProjectStore(
        private val projects: Map<ProjectId, Project> = emptyMap(),
    ) : ProjectStore {
        override suspend fun get(id: ProjectId): Project? = projects[id]
        override suspend fun upsert(title: String, project: Project) = error("not used")
        override suspend fun list(): List<Project> = projects.values.toList()
        override suspend fun delete(id: ProjectId, deleteFiles: Boolean) = error("not used")
        override suspend fun setTitle(id: ProjectId, title: String) = error("not used")
        override suspend fun summary(id: ProjectId): ProjectSummary? = null
        override suspend fun listSummaries(): List<ProjectSummary> = emptyList()
        override suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project = error("not used")
    }

    private fun lockfileEntry(
        inputHash: String,
        assetId: String,
        toolId: String = "generate_image",
        providerId: String = "openai",
        modelId: String = "dall-e-3",
        seed: Long = 42L,
        createdAtEpochMs: Long,
        pinned: Boolean = false,
    ) = LockfileEntry(
        inputHash = inputHash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelId,
            modelVersion = null,
            seed = seed,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAtEpochMs,
        ),
        pinned = pinned,
    )

    private fun session(
        id: String = "s1",
        currentProjectId: String? = null,
    ): Session = Session(
        id = SessionId(id),
        projectId = ProjectId("origin"),
        title = "test",
        createdAt = now,
        updatedAt = now,
        currentProjectId = currentProjectId?.let { ProjectId(it) },
    )

    private fun project(
        id: String = "p1",
        entries: List<LockfileEntry> = emptyList(),
    ): Project = Project(
        id = ProjectId(id),
        timeline = Timeline(),
        lockfile = EagerLockfile(entries = entries),
    )

    // ── session not found → throw ────────────────────────────────

    @Test fun missingSessionThrowsWithSessionIdInMessage() = runTest {
        val sessions = FakeSessionStore()
        val projects = FakeProjectStore()
        val projector = ArtifactTimelineProjector(sessions, projects)

        val ex = assertFailsWith<IllegalStateException> {
            projector.project(SessionId("ghost"))
        }
        assertTrue(
            "ghost" in (ex.message ?: ""),
            "session id surfaces in error; got: ${ex.message}",
        )
        assertTrue(
            "not found" in (ex.message ?: ""),
            "diagnostic phrasing; got: ${ex.message}",
        )
    }

    // ── session unbound → empty UI shape ─────────────────────────

    @Test fun sessionWithoutCurrentProjectReturnsEmptyShapeWithNullProjectId() = runTest {
        // Pin: session exists but hasn't picked a project →
        // empty entries + projectId=null. Distinct from
        // "session not found" (throw) AND from "project
        // missing" (projectId still set).
        val sid = SessionId("s1")
        val sessions = FakeSessionStore(mapOf(sid to session(id = "s1", currentProjectId = null)))
        val projects = FakeProjectStore()
        val projector = ArtifactTimelineProjector(sessions, projects)

        val out = projector.project(sid)
        assertEquals("s1", out.sessionId)
        assertNull(out.projectId, "unbound session → null projectId")
        assertEquals(emptyList(), out.entries)
    }

    // ── currentProjectId set but project missing ────────────────

    @Test fun missingProjectReturnsEmptyShapeWithDanglingProjectIdSurfaced() = runTest {
        // Pin: currentProjectId is set, but the project itself
        // was deleted / never created. UI shape carries the
        // dangling id (so the panel can show "your bound
        // project disappeared" rather than "no project bound").
        // Drift to throw would explode the UI on a relink-
        // pending state.
        val sid = SessionId("s1")
        val sessions = FakeSessionStore(
            mapOf(sid to session(id = "s1", currentProjectId = "phantom")),
        )
        val projects = FakeProjectStore() // empty store
        val projector = ArtifactTimelineProjector(sessions, projects)

        val out = projector.project(sid)
        assertEquals("phantom", out.projectId, "dangling id surfaces, NOT null")
        assertEquals(emptyList(), out.entries)
    }

    // ── happy path: descending sort + entry mapping ─────────────

    @Test fun entriesSortDescendingByProvenanceCreatedAtEpochMs() = runTest {
        // The marquee sort pin: most-recent first matches the
        // session_query / list_lockfile_entries default. Drift
        // to ascending would surface stale early generations
        // at the top of the UI scroll.
        val sid = SessionId("s1")
        val pid = ProjectId("p1")
        val proj = project(
            id = "p1",
            entries = listOf(
                lockfileEntry("h-old", "asset-old", createdAtEpochMs = 100L),
                lockfileEntry("h-new", "asset-new", createdAtEpochMs = 300L),
                lockfileEntry("h-mid", "asset-mid", createdAtEpochMs = 200L),
            ),
        )
        val sessions = FakeSessionStore(
            mapOf(sid to session(id = "s1", currentProjectId = "p1")),
        )
        val projects = FakeProjectStore(mapOf(pid to proj))
        val projector = ArtifactTimelineProjector(sessions, projects)

        val out = projector.project(sid)
        assertEquals(3, out.entries.size)
        // Pin: descending order (newest first).
        assertEquals(
            listOf(300L, 200L, 100L),
            out.entries.map { it.createdAtEpochMs },
            "descending by createdAtEpochMs; got: ${out.entries.map { it.inputHash }}",
        )
    }

    @Test fun entryMappingPreservesAllFieldsIncludingPinned() {
        // Pin: lockfile.LockfileEntry → ArtifactEntry mapping
        // copies inputHash, toolId, assetId, providerId,
        // modelId, seed, createdAtEpochMs, AND the pinned flag.
        // A regression dropping `pinned` would silently strip
        // the user's "keep this" flag from the artifact panel.
        val pinnedEntry = LockfileEntry(
            inputHash = "h-pinned",
            toolId = "generate_video",
            assetId = AssetId("a-pinned"),
            provenance = GenerationProvenance(
                providerId = "replicate",
                modelId = "kling-v1",
                modelVersion = "1.0.0",
                seed = 7L,
                parameters = JsonObject(emptyMap()),
                createdAtEpochMs = 1_700_000_000_000L,
            ),
            pinned = true,
        )
        val proj = project(entries = listOf(pinnedEntry))
        val sessions = FakeSessionStore(mapOf(SessionId("s1") to session(currentProjectId = "p1")))
        val projects = FakeProjectStore(mapOf(ProjectId("p1") to proj))
        val projector = ArtifactTimelineProjector(sessions, projects)

        val out = kotlinx.coroutines.runBlocking { projector.project(SessionId("s1")) }
        val entry = out.entries.single()
        assertEquals("h-pinned", entry.inputHash)
        assertEquals("generate_video", entry.toolId)
        assertEquals("a-pinned", entry.assetId)
        assertEquals("replicate", entry.providerId)
        assertEquals("kling-v1", entry.modelId)
        assertEquals(7L, entry.seed)
        assertEquals(1_700_000_000_000L, entry.createdAtEpochMs)
        assertEquals(true, entry.pinned, "pinned flag preserved")
    }

    @Test fun emptyLockfileProducesEmptyEntries() = runTest {
        // Pin: empty lockfile → empty entries list. NOT null.
        // Distinguishes from unbound-session case (which also
        // returns empty entries but with null projectId).
        val sid = SessionId("s1")
        val pid = ProjectId("p1")
        val proj = project(id = "p1") // no entries
        val sessions = FakeSessionStore(mapOf(sid to session(id = "s1", currentProjectId = "p1")))
        val projects = FakeProjectStore(mapOf(pid to proj))
        val projector = ArtifactTimelineProjector(sessions, projects)

        val out = projector.project(sid)
        assertEquals("p1", out.projectId, "projectId set even when entries empty")
        assertEquals(emptyList(), out.entries)
    }

    // ── shape distinguishes the three empty paths ───────────────

    @Test fun threeEmptyPathsAreDistinguishableByProjectIdField() = runTest {
        // Pin: the three "empty entries" cases produce
        // structurally distinct outputs:
        //   1. unbound session: projectId = null
        //   2. missing project: projectId = "<id>" (dangling)
        //   3. empty lockfile: projectId = "<id>" + entries empty
        // Drift collapsing any two cases would lose UI's
        // ability to show the right state-specific message.

        val sid1 = SessionId("unbound")
        val sid2 = SessionId("dangling")
        val sid3 = SessionId("emptylock")
        val pid = ProjectId("p")
        val proj = project(id = "p")

        val sessions = FakeSessionStore(
            mapOf(
                sid1 to session(id = "unbound", currentProjectId = null),
                sid2 to session(id = "dangling", currentProjectId = "ghost"),
                sid3 to session(id = "emptylock", currentProjectId = "p"),
            ),
        )
        val projects = FakeProjectStore(mapOf(pid to proj))
        val projector = ArtifactTimelineProjector(sessions, projects)

        val unbound = projector.project(sid1)
        val dangling = projector.project(sid2)
        val emptylock = projector.project(sid3)

        assertNull(unbound.projectId)
        assertEquals("ghost", dangling.projectId)
        assertEquals("p", emptylock.projectId)
        assertEquals(emptyList(), unbound.entries)
        assertEquals(emptyList(), dangling.entries)
        assertEquals(emptyList(), emptylock.entries)
    }

    // ── ArtifactTimeline / ArtifactEntry shape ──────────────────

    @Test fun artifactTimelineCarriesSessionIdAlways() = runTest {
        // Pin: sessionId echo is unconditional — present in
        // every empty-state path AND in the happy path. Lets
        // the UI correlate the projection back to its source.
        val sessions = FakeSessionStore(
            mapOf(SessionId("s") to session(id = "s")),
        )
        val out = ArtifactTimelineProjector(sessions, FakeProjectStore()).project(SessionId("s"))
        assertEquals("s", out.sessionId)
    }
}
