package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Clip
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class ProjectMetadataProfile(
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val frameRate: Int,
    val videoCodec: String,
    val audioCodec: String,
)

@Serializable data class ProjectMetadataSnapshotSummary(
    val id: String,
    val label: String,
    val capturedAtEpochMs: Long,
)

/**
 * `select=project_metadata` — single-row drill-down replacing the
 * deleted `describe_project` tool. Compact aggregate across every
 * axis: timeline, tracks-by-kind, clips-by-kind, source-nodes-by-
 * kind, lockfile-by-tool, snapshots, plus a pre-rendered
 * `summaryText` the LLM can quote verbatim.
 */
@Serializable data class ProjectMetadataRow(
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val timelineDurationSeconds: Double,
    val trackCount: Int,
    val tracksByKind: Map<String, Int>,
    val clipCount: Int,
    val clipsByKind: Map<String, Int>,
    val assetCount: Int,
    val sourceNodeCount: Int,
    val sourceNodesByKind: Map<String, Int>,
    val lockfileEntryCount: Int,
    val lockfileByTool: Map<String, Int>,
    val snapshotCount: Int,
    val recentSnapshots: List<ProjectMetadataSnapshotSummary> = emptyList(),
    val outputProfile: ProjectMetadataProfile? = null,
    /**
     * Monotonic source-DAG revision counter — same value the deleted
     * `get_project_state` exposed. Lets the agent detect "did the
     * source layer change since I last looked?" without re-decoding
     * the full DAG. Defaulted to 0 for serialization compat with rows
     * persisted before this field landed.
     */
    val sourceRevision: Long = 0L,
    /**
     * Per-clip mezzanine cache size (FFmpeg engine only — see
     * `ExportTool.runPerClipRender`). Same field the deleted
     * `get_project_state` exposed; useful before a `gc_render_cache`
     * to estimate reclaimable disk. Defaulted to 0 for back-compat.
     */
    val renderCacheEntryCount: Int = 0,
    /** Pre-rendered ~300-char human summary, LLM-quotable verbatim. */
    val summaryText: String,
)

internal const val METADATA_MAX_RECENT_SNAPSHOTS: Int = 5

internal suspend fun runProjectMetadataQuery(
    project: Project,
    projects: ProjectStore,
    input: ProjectQueryTool.Input,
): ToolResult<ProjectQueryTool.Output> {
    @Suppress("UNUSED_PARAMETER")
    val unused = input // keep shape consistent with other handlers; no extra filters
    val meta = projects.summary(project.id)
        ?: error("Project ${project.id.value} has no catalog row — store inconsistency")

    // Track kinds: emit all four so downstream can rely on presence (zeros are informative).
    val tracksByKind = linkedMapOf(
        "video" to 0,
        "audio" to 0,
        "subtitle" to 0,
        "effect" to 0,
    )
    for (track in project.timeline.tracks) {
        val kind = when (track) {
            is Track.Video -> "video"
            is Track.Audio -> "audio"
            is Track.Subtitle -> "subtitle"
            is Track.Effect -> "effect"
        }
        tracksByKind[kind] = (tracksByKind[kind] ?: 0) + 1
    }

    // Clip kinds: three fixed buckets.
    val clipsByKind = linkedMapOf(
        "video" to 0,
        "audio" to 0,
        "text" to 0,
    )
    var clipCount = 0
    for (track in project.timeline.tracks) {
        for (clip in track.clips) {
            clipCount += 1
            val kind = when (clip) {
                is Clip.Video -> "video"
                is Clip.Audio -> "audio"
                is Clip.Text -> "text"
            }
            clipsByKind[kind] = (clipsByKind[kind] ?: 0) + 1
        }
    }

    val sourceNodesByKind: Map<String, Int> = project.source.nodes
        .groupingBy { it.kind }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .associate { it.key to it.value }

    val lockfileByTool: Map<String, Int> = project.lockfile.entries
        .groupingBy { it.toolId }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .associate { it.key to it.value }

    val profile = project.outputProfile
    val profileSummary = if (profile == OutputProfile.DEFAULT_1080P) {
        null
    } else {
        ProjectMetadataProfile(
            resolutionWidth = profile.resolution.width,
            resolutionHeight = profile.resolution.height,
            frameRate = fpsOf(profile),
            videoCodec = profile.videoCodec,
            audioCodec = profile.audioCodec,
        )
    }

    val recentSnapshots = project.snapshots
        .sortedByDescending { it.capturedAtEpochMs }
        .take(METADATA_MAX_RECENT_SNAPSHOTS)
        .map {
            ProjectMetadataSnapshotSummary(
                id = it.id.value,
                label = it.label,
                capturedAtEpochMs = it.capturedAtEpochMs,
            )
        }

    val durationSeconds = project.timeline.duration.inWholeMilliseconds / 1000.0
    val summaryText = renderSummary(
        title = meta.title,
        createdAtEpochMs = meta.createdAtEpochMs,
        profile = profile,
        tracksByKind = tracksByKind,
        clipCount = clipCount,
        durationSeconds = durationSeconds,
        sourceNodesByKind = sourceNodesByKind,
        lockfileEntryCount = project.lockfile.entries.size,
        lockfileByTool = lockfileByTool,
        snapshotCount = project.snapshots.size,
    )

    val row = ProjectMetadataRow(
        title = meta.title,
        createdAtEpochMs = meta.createdAtEpochMs,
        updatedAtEpochMs = meta.updatedAtEpochMs,
        timelineDurationSeconds = durationSeconds,
        trackCount = project.timeline.tracks.size,
        tracksByKind = tracksByKind,
        clipCount = clipCount,
        clipsByKind = clipsByKind,
        assetCount = project.assets.size,
        sourceNodeCount = project.source.nodes.size,
        sourceNodesByKind = sourceNodesByKind,
        lockfileEntryCount = project.lockfile.entries.size,
        lockfileByTool = lockfileByTool,
        snapshotCount = project.snapshots.size,
        recentSnapshots = recentSnapshots,
        outputProfile = profileSummary,
        sourceRevision = project.source.revision,
        renderCacheEntryCount = project.renderCache.entries.size,
        summaryText = summaryText,
    )
    val rows = encodeRows(
        ListSerializer(ProjectMetadataRow.serializer()),
        listOf(row),
    )
    return ToolResult(
        title = "project_query project_metadata ${meta.title}",
        outputForLlm = summaryText,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_PROJECT_METADATA,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}

private fun fpsOf(profile: OutputProfile): Int =
    if (profile.frameRate.denominator == 1) profile.frameRate.numerator
    else profile.frameRate.numerator / profile.frameRate.denominator

private fun renderSummary(
    title: String,
    createdAtEpochMs: Long,
    profile: OutputProfile,
    tracksByKind: Map<String, Int>,
    clipCount: Int,
    durationSeconds: Double,
    sourceNodesByKind: Map<String, Int>,
    lockfileEntryCount: Int,
    lockfileByTool: Map<String, Int>,
    snapshotCount: Int,
): String {
    val trackCount = tracksByKind.values.sum()
    val tracksFragment = tracksByKind.entries
        .filter { it.value > 0 }
        .joinToString(" / ") { "${it.value} ${it.key}" }
        .ifEmpty { "none" }
    val sourceFragment = if (sourceNodesByKind.isEmpty()) {
        "0 source nodes"
    } else {
        val total = sourceNodesByKind.values.sum()
        val breakdown = sourceNodesByKind.entries.joinToString(", ") { "${it.value} ${it.key}" }
        "$total source nodes ($breakdown)"
    }
    val lockfileFragment = if (lockfileEntryCount == 0) {
        "0 lockfile entries"
    } else {
        val breakdown = lockfileByTool.entries.joinToString(", ") { "${it.key}:${it.value}" }
        "$lockfileEntryCount lockfile entries ($breakdown)"
    }
    val resolution = "${profile.resolution.width}x${profile.resolution.height}@${fpsOf(profile)}"
    val createdSeconds = createdAtEpochMs / 1000
    return "Project '$title' (created epoch ${createdSeconds}s): $resolution, " +
        "$trackCount tracks ($tracksFragment), " +
        "$clipCount clips totaling ${formatSeconds(durationSeconds)}s, " +
        "$sourceFragment, $lockfileFragment, $snapshotCount snapshots."
}

private fun formatSeconds(seconds: Double): String {
    val rounded = (seconds * 10).toLong() / 10.0
    val oneDecimal = ((seconds * 10).toLong()).toDouble() / 10.0
    return if (rounded == oneDecimal && oneDecimal == oneDecimal.toLong().toDouble()) {
        oneDecimal.toLong().toString()
    } else {
        val whole = oneDecimal.toLong()
        val tenth = ((seconds * 10).toLong() - whole * 10).let { if (it < 0) -it else it }
        "$whole.$tenth"
    }
}
