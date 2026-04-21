package io.talevia.core.session.projector

import io.talevia.core.SessionId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.session.SessionStore
import kotlinx.serialization.Serializable

/**
 * Time-ordered audit of AIGC artifacts produced in the project bound to a
 * session. UI shape for the "what have I made so far?" panel — chronological
 * list of lockfile entries with their provenance (toolId, provider, model,
 * seed-adjacent timestamps) so the user can scroll the generation history.
 *
 * Session → project binding: uses [io.talevia.core.session.Session.currentProjectId].
 * If a session hasn't picked a project yet, [ArtifactTimeline.projectId] is
 * `null` and [entries] is empty (vs erroring — UIs would rather show an empty
 * state than explode at render time).
 *
 * Sorted by `provenance.createdAtEpochMs` descending (most-recent first) so
 * the newest generation lands at the top of the scroll, matching the
 * `session_query(select=parts)` / list_lockfile_entries default ordering.
 */
@Serializable
data class ArtifactTimeline(
    val sessionId: String,
    /** `null` when the session has no currentProjectId yet. */
    val projectId: String? = null,
    val entries: List<ArtifactEntry> = emptyList(),
)

@Serializable
data class ArtifactEntry(
    val inputHash: String,
    val toolId: String,
    val assetId: String,
    val providerId: String,
    val modelId: String,
    val seed: Long,
    val createdAtEpochMs: Long,
    val pinned: Boolean,
)

class ArtifactTimelineProjector(
    private val sessions: SessionStore,
    private val projects: ProjectStore,
) : SessionProjector<ArtifactTimeline> {

    override suspend fun project(sessionId: SessionId): ArtifactTimeline {
        val session = sessions.getSession(sessionId)
            ?: error(
                "Session ${sessionId.value} not found. Cannot project artifacts for a non-existent session.",
            )
        val pid = session.currentProjectId
            ?: return ArtifactTimeline(sessionId = sessionId.value, projectId = null, entries = emptyList())
        val project = projects.get(pid)
            ?: return ArtifactTimeline(sessionId = sessionId.value, projectId = pid.value, entries = emptyList())

        val entries = project.lockfile.entries
            .sortedByDescending { it.provenance.createdAtEpochMs }
            .map { e ->
                ArtifactEntry(
                    inputHash = e.inputHash,
                    toolId = e.toolId,
                    assetId = e.assetId.value,
                    providerId = e.provenance.providerId,
                    modelId = e.provenance.modelId,
                    seed = e.provenance.seed,
                    createdAtEpochMs = e.provenance.createdAtEpochMs,
                    pinned = e.pinned,
                )
            }
        return ArtifactTimeline(
            sessionId = sessionId.value,
            projectId = pid.value,
            entries = entries,
        )
    }
}
