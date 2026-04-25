package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.template.IntentClassifier
import io.talevia.core.tool.builtin.project.template.seedAdTemplate
import io.talevia.core.tool.builtin.project.template.seedMusicMvTemplate
import io.talevia.core.tool.builtin.project.template.seedNarrativeTemplate
import io.talevia.core.tool.builtin.project.template.seedTutorialTemplate
import io.talevia.core.tool.builtin.project.template.seedVlogTemplate
import okio.Path.Companion.toPath

/**
 * `project_action(action="create_from_template", …)` dispatch handler.
 *
 * Cycle 140 absorbed the standalone `create_project_from_template` tool
 * into the project_action dispatcher, mirroring the precedent set by
 * the cycle 137-139 fold series (`describe_source_node` → `source_query`,
 * `find_stale_clips` / `validate_project` → `project_query`). Same
 * Atomic semantics as before: the tool composes the genre helpers in
 * one `ProjectStore.upsert` (or one `createAt` + `mutate` when `path`
 * is set). Fails loud on duplicate project id.
 *
 * Five templates, one per genre schema in
 * `core/domain/source/genre/`. Each genre's seed body is extracted
 * into its own `template/<genre>.kt` file so adding a genre is a new
 * file + one `when` branch in [dispatchTemplate], not a 50-line
 * insertion mid-handler.
 *
 * `template = "auto"` requires `intent` and runs on-device keyword
 * classification via [IntentClassifier] — no LLM round-trip.
 *
 * Permission: inherits the dispatcher's base `project.write` tier
 * (no per-action override needed — same level as `action="create"`).
 */
internal suspend fun executeCreateFromTemplate(
    projects: ProjectStore,
    input: ProjectActionTool.Input,
    @Suppress("UNUSED_PARAMETER") ctx: ToolContext,
): ToolResult<ProjectActionTool.Output> {
    val title = input.title
    require(!title.isNullOrBlank()) { "title must not be blank for action='create_from_template'" }
    val rawTemplate = input.template
    require(!rawTemplate.isNullOrBlank()) {
        "template must not be blank for action='create_from_template' " +
            "(accepted: narrative, vlog, ad, musicmv, tutorial, auto)"
    }

    val resolution = parseResolution(input.resolutionPreset)
    val frameRate = parseFrameRate(input.fps)
    val profile = OutputProfile(resolution = resolution, frameRate = frameRate)
    val timeline = Timeline(resolution = resolution, frameRate = frameRate)

    val resolved = resolveTemplate(rawTemplate, input.intent)
    val (source, seededIds) = dispatchTemplate(resolved.template)

    val pid: ProjectId = if (!input.path.isNullOrBlank()) {
        // createAt only accepts timeline + outputProfile; layer the
        // template's source DAG on via mutate after the bundle exists.
        val created = projects.createAt(
            path = input.path.toPath(),
            title = title,
            timeline = timeline,
            outputProfile = profile,
        )
        projects.mutate(created.id) { it.copy(source = source) }
        created.id
    } else {
        val rawId = input.projectId?.takeIf { it.isNotBlank() } ?: slugifyProjectId(title)
        val candidate = ProjectId(rawId)
        require(projects.get(candidate) == null) {
            "project ${candidate.value} already exists; use project_query(select=projects-equivalent " +
                "list_projects) to find an unused id or operate on the existing one"
        }
        val project = Project(
            id = candidate,
            timeline = timeline,
            outputProfile = profile,
            source = source,
        )
        projects.upsert(title, project)
        candidate
    }

    val result = ProjectActionTool.CreateFromTemplateResult(
        title = title,
        template = resolved.template,
        resolutionWidth = resolution.width,
        resolutionHeight = resolution.height,
        fps = frameRate.numerator,
        seededNodeIds = seededIds,
        inferredFromIntent = resolved.inferred,
        inferredReason = resolved.reason,
    )
    val inferenceNote = if (resolved.inferred) " (auto-inferred: ${resolved.reason})" else ""
    val pathNote = input.path?.let { " at $it" }.orEmpty()
    return ToolResult(
        title = "create project $title from ${resolved.template}",
        outputForLlm = "Created project ${pid.value} (\"$title\")$pathNote at " +
            "${resolution.width}x${resolution.height}@${frameRate.numerator}fps " +
            "from ${resolved.template} template$inferenceNote. Seeded ${seededIds.size} source node(s): " +
            "${seededIds.joinToString(", ")}. Fill in TODO placeholders via update_* tools " +
            "before the first AIGC call.",
        data = ProjectActionTool.Output(
            projectId = pid.value,
            action = "create_from_template",
            createFromTemplateResult = result,
        ),
    )
}

private data class ResolvedTemplate(val template: String, val inferred: Boolean, val reason: String?)

/**
 * Normalise the `template` field and run classification when needed.
 * `"auto"` fails loud if `intent` is missing — silent narrative
 * fallback would hide novice-mode misuse. The classifier itself does
 * no LLM calls, so this stays a pure function of `(template, intent)`.
 */
private fun resolveTemplate(rawTemplate: String, rawIntent: String?): ResolvedTemplate {
    val raw = rawTemplate.trim().lowercase()
    if (raw != "auto") return ResolvedTemplate(raw, inferred = false, reason = null)
    val intent = rawIntent?.trim()
    require(!intent.isNullOrEmpty()) {
        "template='auto' requires a non-blank `intent` string (the user's one-sentence ask)"
    }
    val classification = IntentClassifier.classify(intent)
    return ResolvedTemplate(classification.template, inferred = true, reason = classification.reason)
}

/**
 * Dispatch to the per-genre seed helper. Each genre lives in its own
 * file under `project/template/` — adding a new genre is one new file
 * + one `when` branch here.
 */
private fun dispatchTemplate(template: String): Pair<Source, List<String>> =
    when (template.lowercase()) {
        "narrative" -> seedNarrativeTemplate()
        "vlog" -> seedVlogTemplate()
        "ad" -> seedAdTemplate()
        "musicmv" -> seedMusicMvTemplate()
        "tutorial" -> seedTutorialTemplate()
        else -> throw IllegalArgumentException(
            "unknown template '$template'; accepted: narrative, vlog, ad, musicmv, tutorial",
        )
    }

private fun parseResolution(preset: String?): Resolution = when (preset?.lowercase()) {
    null, "", "1080p" -> Resolution(1920, 1080)
    "720p" -> Resolution(1280, 720)
    "4k", "2160p" -> Resolution(3840, 2160)
    else -> throw IllegalArgumentException(
        "unknown resolutionPreset '$preset'; accepted: 720p, 1080p, 4k",
    )
}

private fun parseFrameRate(fps: Int?): FrameRate = when (fps) {
    null, 30 -> FrameRate.FPS_30
    24 -> FrameRate.FPS_24
    60 -> FrameRate.FPS_60
    else -> throw IllegalArgumentException("unsupported fps=$fps; accepted: 24, 30, 60")
}
