package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.template.IntentClassifier
import io.talevia.core.tool.builtin.project.template.seedAdTemplate
import io.talevia.core.tool.builtin.project.template.seedMusicMvTemplate
import io.talevia.core.tool.builtin.project.template.seedNarrativeTemplate
import io.talevia.core.tool.builtin.project.template.seedTutorialTemplate
import io.talevia.core.tool.builtin.project.template.seedVlogTemplate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import okio.Path.Companion.toPath

/**
 * Bootstrap a project *pre-populated* with a genre skeleton (VISION §5.4
 * novice path). `create_project` alone leaves a blank source DAG — the
 * agent then has to `set_character_ref` + `set_style_bible` + …
 * before it can do anything useful with AIGC. This tool inverts the order:
 * pick a genre template, get a working source graph with placeholder
 * character / style / scene stubs, then refine.
 *
 * Five templates, one per genre schema that ships in
 * `core/domain/source/genre/`. Each genre's seed body is extracted into its
 * own `project/template/<genre>.kt` file so adding a genre is a new file +
 * one `when` branch here, not a 50-line insertion mid-file.
 *
 * All seeded nodes use `TODO: …` placeholders so the agent / expert user
 * can tell what still needs filling in. We deliberately avoid opinionated
 * defaults ("graduation day", "sunset walk") that would bias the user's
 * creative direction.
 *
 * Atomic: this tool just composes the genre helpers in one
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
        /**
         * Genre template id: `"narrative"`, `"vlog"`, `"ad"`, `"musicmv"`,
         * `"tutorial"`, or `"auto"`. When `"auto"`, [intent] is required
         * and drives classification via [IntentClassifier].
         */
        val template: String,
        val projectId: String? = null,
        val resolutionPreset: String? = null,
        val fps: Int? = null,
        /**
         * Free-form user intent — required when `template = "auto"`, ignored
         * otherwise. Parsed by a deterministic keyword classifier on-device;
         * no LLM round-trip. VISION §5.4 novice-path primitive: the agent /
         * user types one sentence, the tool picks a genre and seeds the
         * source DAG.
         */
        val intent: String? = null,
        /**
         * Optional filesystem path for the new bundle. See
         * [CreateProjectTool.Input.path] for semantics. The template is
         * seeded into the bundle via a follow-up [ProjectStore.mutate] when
         * `path` is set, since [ProjectStore.createAt] only takes timeline +
         * outputProfile.
         */
        val path: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        /** Echo of the template actually seeded (resolved from `auto` when applicable). */
        val template: String,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
        val seededNodeIds: List<String>,
        /**
         * Set only when the caller passed `template = "auto"`. Explains
         * which keywords drove classification so the agent can redo with
         * an explicit template if the inference was wrong.
         */
        val inferredFromIntent: Boolean = false,
        val inferredReason: String? = null,
    )

    override val id: String = "create_project_from_template"
    override val helpText: String =
        "Create a new project and seed its source DAG with a genre template so the " +
            "agent doesn't start from zero nodes. Templates: narrative / vlog / ad / " +
            "musicmv / tutorial. Pass template='auto' plus an `intent` string to let " +
            "the tool classify the genre from keywords (novice path — one call, " +
            "source DAG pre-populated). All seeded nodes use TODO placeholders — replace " +
            "them via update_source_node_body / set_character_ref / set_style_bible / " +
            "set_brand_palette before the first AIGC call. The musicmv template does " +
            "not seed a musicmv.track node (needs an imported music asset first). " +
            "Resolution + fps options same as create_project."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string"); put("description", "Human-readable project title.") }
            putJsonObject("template") {
                put("type", "string")
                put(
                    "description",
                    "Genre template: 'narrative', 'vlog', 'ad', 'musicmv', 'tutorial', or 'auto'. " +
                        "All non-auto templates seed the source DAG with placeholder nodes the agent / user " +
                        "then fills in. 'auto' requires `intent` and classifies the genre from keywords.",
                )
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
            putJsonObject("intent") {
                put("type", "string")
                put(
                    "description",
                    "One-sentence user intent. Required when template='auto'; ignored otherwise. " +
                        "Used to classify the genre via on-device keyword match (no LLM round-trip).",
                )
            }
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description",
                    "Optional absolute filesystem path for the project bundle. Defaults to the " +
                        "store's default-projects-home. The directory must not already contain " +
                        "a talevia.json.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("template"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.title.isNotBlank()) { "title must not be blank" }
        val resolution = parseResolution(input.resolutionPreset)
        val frameRate = parseFrameRate(input.fps)
        val profile = OutputProfile(resolution = resolution, frameRate = frameRate)
        val timeline = Timeline(resolution = resolution, frameRate = frameRate)

        val resolved = resolveTemplate(input)
        val (source, seededIds) = dispatchTemplate(resolved.template)

        val pid: ProjectId = if (input.path != null && input.path.isNotBlank()) {
            // createAt only accepts timeline + outputProfile; layer the
            // template's source DAG on via mutate after the bundle exists.
            val created = projects.createAt(
                path = input.path.toPath(),
                title = input.title,
                timeline = timeline,
                outputProfile = profile,
            )
            projects.mutate(created.id) { it.copy(source = source) }
            created.id
        } else {
            val rawId = input.projectId?.takeIf { it.isNotBlank() } ?: slugifyProjectId(input.title)
            val candidate = ProjectId(rawId)
            require(projects.get(candidate) == null) {
                "project ${candidate.value} already exists; use list_projects to find an unused id or operate on the existing one"
            }
            val project = Project(id = candidate, timeline = timeline, outputProfile = profile, source = source)
            projects.upsert(input.title, project)
            candidate
        }

        val out = Output(
            projectId = pid.value,
            title = input.title,
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
            title = "create project ${input.title} from ${resolved.template}",
            outputForLlm = "Created project ${pid.value} (\"${input.title}\")$pathNote at " +
                "${resolution.width}x${resolution.height}@${frameRate.numerator}fps " +
                "from ${resolved.template} template$inferenceNote. Seeded ${seededIds.size} source node(s): " +
                "${seededIds.joinToString(", ")}. Fill in TODO placeholders via update_* tools " +
                "before the first AIGC call.",
            data = out,
        )
    }

    private data class ResolvedTemplate(val template: String, val inferred: Boolean, val reason: String?)

    /**
     * Normalise the `template` field and run classification when needed.
     * `"auto"` fails loud if `intent` is missing — silent narrative
     * fallback would hide novice-mode misuse. The classifier itself does
     * no LLM calls, so this stays a pure function of `(template, intent)`.
     */
    private fun resolveTemplate(input: Input): ResolvedTemplate {
        val raw = input.template.trim().lowercase()
        if (raw != "auto") return ResolvedTemplate(raw, inferred = false, reason = null)
        val intent = input.intent?.trim()
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
}
