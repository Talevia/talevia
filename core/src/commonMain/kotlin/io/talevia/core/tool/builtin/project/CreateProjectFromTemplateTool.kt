package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.genre.narrative.NarrativeSceneBody
import io.talevia.core.domain.source.genre.narrative.NarrativeShotBody
import io.talevia.core.domain.source.genre.narrative.NarrativeStorylineBody
import io.talevia.core.domain.source.genre.narrative.NarrativeWorldBody
import io.talevia.core.domain.source.genre.narrative.addNarrativeScene
import io.talevia.core.domain.source.genre.narrative.addNarrativeShot
import io.talevia.core.domain.source.genre.narrative.addNarrativeStoryline
import io.talevia.core.domain.source.genre.narrative.addNarrativeWorld
import io.talevia.core.domain.source.genre.vlog.VlogEditIntentBody
import io.talevia.core.domain.source.genre.vlog.VlogRawFootageBody
import io.talevia.core.domain.source.genre.vlog.VlogStylePresetBody
import io.talevia.core.domain.source.genre.vlog.addVlogEditIntent
import io.talevia.core.domain.source.genre.vlog.addVlogRawFootage
import io.talevia.core.domain.source.genre.vlog.addVlogStylePreset
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
 * Bootstrap a project *pre-populated* with a genre skeleton (VISION §5.4
 * novice path). `create_project` alone leaves a blank source DAG — the
 * agent then has to `define_character_ref` + `define_style_bible` + …
 * before it can do anything useful with AIGC. This tool inverts the order:
 * pick a genre template, get a working source graph with placeholder
 * character / style / scene stubs, then refine.
 *
 * Two templates today, matching the two genre schemas that ship in
 * `core/domain/source/genre/`:
 *   - `narrative` → world + storyline + one scene stub + one shot stub +
 *     one character_ref + one style_bible. Wired together via `parents`
 *     so DAG propagation works from day zero.
 *   - `vlog` → raw_footage + edit_intent + style_preset + one style_bible.
 *
 * Both templates put stringly-typed placeholders ("TODO: …") in the
 * descriptive fields so the agent / expert user can tell what still needs
 * filling in. We deliberately avoid opinionated defaults ("graduation
 * day", "sunset walk") that would bias the user's creative direction.
 *
 * All the heavy lifting is in the existing genre ext helpers + consistency
 * builders; this tool just composes them atomically in one
 * `ProjectStore.upsert`. Fails loud on duplicate project id the same way
 * `create_project` does.
 *
 * Permission: `"project.write"` — same as `create_project`.
 */
class CreateProjectFromTemplateTool(
    private val projects: ProjectStore,
) : Tool<CreateProjectFromTemplateTool.Input, CreateProjectFromTemplateTool.Output> {

    @Serializable data class Input(
        val title: String,
        val template: String,
        val projectId: String? = null,
        val resolutionPreset: String? = null,
        val fps: Int? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        val template: String,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
        val seededNodeIds: List<String>,
    )

    override val id: String = "create_project_from_template"
    override val helpText: String =
        "Create a new project and seed its source DAG with a genre template so the " +
            "agent doesn't start from zero nodes. Templates: narrative (world + " +
            "storyline + scene + shot + character_ref + style_bible) or vlog (raw_" +
            "footage + edit_intent + style_preset + style_bible). All seeded nodes " +
            "use TODO placeholders — replace them via define_/update_ tools before " +
            "the first AIGC call. Resolution + fps options same as create_project."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string"); put("description", "Human-readable project title.") }
            putJsonObject("template") {
                put("type", "string")
                put("description", "Genre template: 'narrative' or 'vlog'. Both seed the source DAG with placeholder nodes the agent / user then fills in.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Optional explicit id; defaults to a slugged variant of title.")
            }
            putJsonObject("resolutionPreset") {
                put("type", "string")
                put("description", "Output resolution: 720p, 1080p (default), or 4k.")
            }
            putJsonObject("fps") {
                put("type", "integer")
                put("description", "Output frame rate: 24, 30 (default), or 60.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("template"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.title.isNotBlank()) { "title must not be blank" }
        val rawId = input.projectId?.takeIf { it.isNotBlank() } ?: slugifyProjectId(input.title)
        val pid = ProjectId(rawId)
        require(projects.get(pid) == null) {
            "project ${pid.value} already exists; use list_projects to find an unused id or operate on the existing one"
        }
        val resolution = parseResolution(input.resolutionPreset)
        val frameRate = parseFrameRate(input.fps)
        val profile = OutputProfile(resolution = resolution, frameRate = frameRate)
        val timeline = Timeline(resolution = resolution, frameRate = frameRate)

        val (source, seededIds) = when (input.template.lowercase()) {
            "narrative" -> seedNarrative()
            "vlog" -> seedVlog()
            else -> throw IllegalArgumentException(
                "unknown template '${input.template}'; accepted: narrative, vlog",
            )
        }

        val project = Project(id = pid, timeline = timeline, outputProfile = profile, source = source)
        projects.upsert(input.title, project)

        val out = Output(
            projectId = pid.value,
            title = input.title,
            template = input.template.lowercase(),
            resolutionWidth = resolution.width,
            resolutionHeight = resolution.height,
            fps = frameRate.numerator,
            seededNodeIds = seededIds,
        )
        return ToolResult(
            title = "create project ${input.title} from ${input.template}",
            outputForLlm = "Created project ${pid.value} (\"${input.title}\") at " +
                "${resolution.width}x${resolution.height}@${frameRate.numerator}fps " +
                "from ${input.template} template. Seeded ${seededIds.size} source node(s): " +
                "${seededIds.joinToString(", ")}. Fill in TODO placeholders via update_* tools " +
                "before the first AIGC call.",
            data = out,
        )
    }

    private fun seedNarrative(): Pair<Source, List<String>> {
        val worldId = SourceNodeId("world-1")
        val storyId = SourceNodeId("story-1")
        val sceneId = SourceNodeId("scene-1")
        val shotId = SourceNodeId("shot-1")
        val characterId = SourceNodeId("protagonist")
        val styleId = SourceNodeId("style")

        var s: Source = Source.EMPTY
            .addCharacterRef(
                characterId,
                CharacterRefBody(name = "protagonist", visualDescription = "TODO: describe the protagonist"),
            )
            .addStyleBible(
                styleId,
                StyleBibleBody(name = "style", description = "TODO: describe the visual style"),
            )
            .addNarrativeWorld(
                worldId,
                NarrativeWorldBody(name = "world", description = "TODO: describe the world / setting"),
                parents = listOf(SourceRef(styleId)),
            )
            .addNarrativeStoryline(
                storyId,
                NarrativeStorylineBody(logline = "TODO: one-sentence pitch"),
                parents = listOf(SourceRef(worldId)),
            )
            .addNarrativeScene(
                sceneId,
                NarrativeSceneBody(
                    title = "opening",
                    action = "TODO: describe what happens in the opening scene",
                    characterIds = listOf(characterId.value),
                ),
                parents = listOf(SourceRef(storyId), SourceRef(characterId)),
            )
            .addNarrativeShot(
                shotId,
                NarrativeShotBody(
                    sceneId = sceneId.value,
                    framing = "wide",
                    action = "TODO: describe the first shot",
                ),
                parents = listOf(SourceRef(sceneId)),
            )
        return s to listOf(
            characterId.value, styleId.value, worldId.value,
            storyId.value, sceneId.value, shotId.value,
        )
    }

    private fun seedVlog(): Pair<Source, List<String>> {
        val intentId = SourceNodeId("intent")
        val footageId = SourceNodeId("footage")
        val presetId = SourceNodeId("style-preset")
        val styleId = SourceNodeId("style")

        val s: Source = Source.EMPTY
            .addStyleBible(
                styleId,
                StyleBibleBody(name = "style", description = "TODO: describe the visual style"),
            )
            .addVlogRawFootage(
                footageId,
                VlogRawFootageBody(assetIds = emptyList(), notes = "TODO: import footage and bind assetIds here"),
            )
            .addVlogEditIntent(
                intentId,
                VlogEditIntentBody(description = "TODO: describe the editing intent / mood"),
            )
            .addVlogStylePreset(
                presetId,
                VlogStylePresetBody(name = "style-preset"),
            )
        return s to listOf(styleId.value, footageId.value, intentId.value, presetId.value)
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
}
