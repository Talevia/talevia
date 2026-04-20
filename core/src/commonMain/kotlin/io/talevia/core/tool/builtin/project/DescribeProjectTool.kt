package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Compact, LLM-friendly aggregate of the entire project state — the VISION §3.4 / §5.4
 * "可读" (readable) surface an agent can query when resuming a session to orient itself
 * in a single call.
 *
 * [GetProjectStateTool] already reports numeric counts, and the various `list_*` tools
 * each drill into one axis (clips, assets, source nodes, lockfile entries, snapshots).
 * What was missing is a single compact summary blending **all** axes — tracks-by-kind,
 * clips-by-kind, source-nodes-by-kind, lockfile-by-tool, plus a pre-rendered
 * one-paragraph [Output.summaryText] the agent can echo verbatim into its context.
 *
 * Example [Output.summaryText]:
 * > Project 'my-vlog' (created 2026-04-01): 1920x1080@30, 3 tracks (2 video / 1 audio),
 * > 8 clips totaling 45.2s, 5 source nodes (3 character_ref, 2 style_bible), 12
 * > lockfile entries (generate_image:8, synthesize_speech:4), 2 snapshots.
 *
 * Output profile surfacing: the [io.talevia.core.domain.Project.outputProfile] field is
 * never structurally null (it defaults to [OutputProfile.DEFAULT_1080P]). We surface
 * [Output.outputProfile] only when the project's profile differs from the default —
 * so a newly-created project reads "unset" (null), while a project that's had
 * `set_output_profile` called on it reads the concrete spec. This matches the rubric's
 * intent of "did the user actually pick a render target yet?".
 *
 * Read-only — permission `project.read`. Missing project throws `IllegalStateException`.
 */
class DescribeProjectTool(
    private val projects: ProjectStore,
) : Tool<DescribeProjectTool.Input, DescribeProjectTool.Output> {

    @Serializable data class Input(val projectId: String)

    @Serializable data class ProfileSummary(
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val frameRate: Int,
        val videoCodec: String,
        val audioCodec: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val timelineDurationSeconds: Double,
        val trackCount: Int,
        /** "video" -> 2, "audio" -> 1, "subtitle" -> 1, "effect" -> 0. Every kind always present. */
        val tracksByKind: Map<String, Int>,
        val clipCount: Int,
        /** "video" -> 5, "audio" -> 2, "text" -> 3. Every kind always present. */
        val clipsByKind: Map<String, Int>,
        val assetCount: Int,
        val sourceNodeCount: Int,
        /** Sparse — only the kinds that actually appear, sorted alphabetically. */
        val sourceNodesByKind: Map<String, Int>,
        val lockfileEntryCount: Int,
        /** Sparse — only the toolIds that actually appear, sorted alphabetically. */
        val lockfileByTool: Map<String, Int>,
        val snapshotCount: Int,
        /** Non-null only when the profile differs from [OutputProfile.DEFAULT_1080P]. */
        val outputProfile: ProfileSummary?,
        /** Pre-rendered ~300-char human summary, LLM-quotable verbatim. */
        val summaryText: String,
    )

    override val id: String = "describe_project"
    override val helpText: String =
        "Compact, one-paragraph summary of a project across every axis: title, dimensions, tracks " +
            "(by kind), clips (by kind), duration, source-DAG size (by kind), lockfile entries " +
            "(by tool), snapshot count, output profile. Cheaper to read than `get_project_state` + " +
            "several `list_*` calls — use this when resuming a session or before planning multi-step " +
            "edits. `summaryText` is LLM-quotable."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val meta = projects.summary(pid)
            ?: error("Project ${input.projectId} has no catalog row — store inconsistency")

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

        // Clip kinds: three fixed buckets, same rationale.
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

        // Source nodes: sparse — only the kinds actually used, sorted for stability.
        // Using a LinkedHashMap populated from a sorted entry list keeps ordering in commonMain
        // (no JVM-only `toSortedMap` helper).
        val sourceNodesByKind: Map<String, Int> = project.source.nodes
            .groupingBy { it.kind }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .associate { it.key to it.value }

        // Lockfile: sparse — grouped by toolId, sorted.
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
            ProfileSummary(
                resolutionWidth = profile.resolution.width,
                resolutionHeight = profile.resolution.height,
                frameRate = fpsOf(profile),
                videoCodec = profile.videoCodec,
                audioCodec = profile.audioCodec,
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

        val out = Output(
            projectId = pid.value,
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
            outputProfile = profileSummary,
            summaryText = summaryText,
        )
        return ToolResult(
            title = "describe project ${meta.title}",
            outputForLlm = summaryText,
            data = out,
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
        // One decimal place for readability; strip trailing ".0" when integral.
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
}
