package io.talevia.core.tool.builtin

import io.ktor.client.HttpClient
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStore
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.FileSystem
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.ProcessRunner
import io.talevia.core.platform.ProxyGenerator
import io.talevia.core.platform.SearchEngine
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.VideoEngine
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VisionEngine
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.CompareAigcCandidatesTool
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.GenerateMusicTool
import io.talevia.core.tool.builtin.aigc.GenerateVideoTool
import io.talevia.core.tool.builtin.aigc.ReplayLockfileTool
import io.talevia.core.tool.builtin.aigc.SynthesizeSpeechTool
import io.talevia.core.tool.builtin.aigc.UpscaleAssetTool
import io.talevia.core.tool.builtin.fs.EditTool
import io.talevia.core.tool.builtin.fs.GlobTool
import io.talevia.core.tool.builtin.fs.GrepTool
import io.talevia.core.tool.builtin.fs.ListDirectoryTool
import io.talevia.core.tool.builtin.fs.MultiEditTool
import io.talevia.core.tool.builtin.fs.ReadFileTool
import io.talevia.core.tool.builtin.fs.WriteFileTool
import io.talevia.core.tool.builtin.meta.EstimateTokensTool
import io.talevia.core.tool.builtin.meta.ListToolsTool
import io.talevia.core.tool.builtin.ml.DescribeAssetTool
import io.talevia.core.tool.builtin.ml.TranscribeAssetTool
import io.talevia.core.tool.builtin.project.CreateProjectFromTemplateTool
import io.talevia.core.tool.builtin.project.CreateProjectTool
import io.talevia.core.tool.builtin.project.DeleteProjectTool
import io.talevia.core.tool.builtin.project.DiffProjectsTool
import io.talevia.core.tool.builtin.project.ExportProjectTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ImportProjectFromJsonTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.OpenProjectTool
import io.talevia.core.tool.builtin.project.ProjectMaintenanceActionTool
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.ProjectSnapshotActionTool
import io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool
import io.talevia.core.tool.builtin.project.RemoveAssetTool
import io.talevia.core.tool.builtin.project.RenameProjectTool
import io.talevia.core.tool.builtin.project.SetClipAssetPinnedTool
import io.talevia.core.tool.builtin.project.SetLockfileEntryPinnedTool
import io.talevia.core.tool.builtin.project.SetOutputProfileTool
import io.talevia.core.tool.builtin.project.ValidateProjectTool
import io.talevia.core.tool.builtin.session.EstimateSessionTokensTool
import io.talevia.core.tool.builtin.session.ExportSessionTool
import io.talevia.core.tool.builtin.session.ForkSessionTool
import io.talevia.core.tool.builtin.session.ReadPartTool
import io.talevia.core.tool.builtin.session.RevertSessionTool
import io.talevia.core.tool.builtin.session.SessionActionTool
import io.talevia.core.tool.builtin.session.SessionQueryTool
import io.talevia.core.tool.builtin.session.SetSessionSpendCapTool
import io.talevia.core.tool.builtin.session.SetToolEnabledTool
import io.talevia.core.tool.builtin.session.SwitchProjectTool
import io.talevia.core.tool.builtin.shell.BashTool
import io.talevia.core.tool.builtin.source.AddSourceNodeTool
import io.talevia.core.tool.builtin.source.DescribeSourceNodeTool
import io.talevia.core.tool.builtin.source.DiffSourceNodesTool
import io.talevia.core.tool.builtin.source.ExportSourceNodeTool
import io.talevia.core.tool.builtin.source.ForkSourceNodeTool
import io.talevia.core.tool.builtin.source.ImportSourceNodeTool
import io.talevia.core.tool.builtin.source.RemoveSourceNodeTool
import io.talevia.core.tool.builtin.source.RenameSourceNodeTool
import io.talevia.core.tool.builtin.source.SetSourceNodeParentsTool
import io.talevia.core.tool.builtin.source.SourceQueryTool
import io.talevia.core.tool.builtin.source.UpdateSourceNodeBodyTool
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.ApplyLutTool
import io.talevia.core.tool.builtin.video.AutoSubtitleClipTool
import io.talevia.core.tool.builtin.video.ClearTimelineTool
import io.talevia.core.tool.builtin.video.ClipActionTool
import io.talevia.core.tool.builtin.video.ConsolidateMediaIntoBundleTool
import io.talevia.core.tool.builtin.video.DuplicateTrackTool
import io.talevia.core.tool.builtin.video.EditTextClipTool
import io.talevia.core.tool.builtin.video.ExportDryRunTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ExtractFrameTool
import io.talevia.core.tool.builtin.video.FadeAudioClipTool
import io.talevia.core.tool.builtin.video.FilterActionTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.MoveClipTool
import io.talevia.core.tool.builtin.video.RelinkAssetTool
import io.talevia.core.tool.builtin.video.ReorderTracksTool
import io.talevia.core.tool.builtin.video.ReplaceClipTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import io.talevia.core.tool.builtin.video.SetClipSourceBindingTool
import io.talevia.core.tool.builtin.video.SetClipTransformTool
import io.talevia.core.tool.builtin.video.SetClipVolumeTool
import io.talevia.core.tool.builtin.video.SplitClipTool
import io.talevia.core.tool.builtin.video.TrackActionTool
import io.talevia.core.tool.builtin.video.TransitionActionTool
import io.talevia.core.tool.builtin.video.TrimClipTool
import io.talevia.core.tool.builtin.web.WebFetchTool
import io.talevia.core.tool.builtin.web.WebSearchTool

/**
 * Shared tool-registration extensions for every [ToolRegistry]-owning
 * composition root (CLI / Desktop / Server / Android). One place, one
 * truth — `debt-cross-container-tool-list-builder` (2026-04-23).
 *
 * Before this file existed, `apps/cli/.../CliContainerTools.kt` and
 * `apps/server/.../ServerContainerTools.kt` carried byte-identical
 * copies of the 7 extensions below (see commit log for
 * `CliContainerTools.kt` + `ServerContainerTools.kt` — both landed in
 * cycle 12 / 20's split-container decisions, then drifted in
 * lockstep). Desktop's `AppContainer` + Android's `AndroidAppContainer`
 * inlined the same registrations directly in their `apply { ... }`
 * blocks. Adding a new Core tool required 4 × {import insertion +
 * register-line insertion + ktlint-sort pass}; a single miss silently
 * dropped the tool on one platform.
 *
 * The 7 extensions below own the common registrations every container
 * wants. Each takes the concrete dependencies it actually needs — no
 * god-object container ref. Composition roots call whichever subset
 * applies and overlay platform-specific or provider-gated tools on
 * top (e.g. Server registers `ProviderQueryTool` + `CompactSessionTool`
 * in a second pass because those depend on `ProviderRegistry`, which
 * itself is built from the same container).
 *
 * Two tools (`ProviderQueryTool`, `CompactSessionTool`) are
 * intentionally NOT in here — they depend on `ProviderRegistry`,
 * which is built from the same container in a second pass. Keeping
 * them in the container's `init {}` block keeps this file's deps
 * acyclic.
 */

/**
 * Meta introspection + session lifecycle + agent / todo / plan tools.
 * Uses `this` (the registry) for tools that self-reference the registry
 * (`ListToolsTool`, `ExecutePlanTool`, `SessionQueryTool`).
 */
fun ToolRegistry.registerSessionAndMetaTools(
    sessions: SessionStore,
    agentStates: AgentRunStateTracker,
    projects: ProjectStore,
    bus: EventBus,
    fallbackTracker: io.talevia.core.agent.AgentProviderFallbackTracker? = null,
) {
    register(ListToolsTool(this))
    register(EstimateTokensTool())
    register(TodoWriteTool())
    register(DraftPlanTool())
    register(ExecutePlanTool(this, sessions))
    register(
        SessionQueryTool(
            sessions,
            agentStates,
            projects,
            toolRegistry = this,
            fallbackTracker = fallbackTracker,
        ),
    )
    register(ExportSessionTool(sessions))
    register(EstimateSessionTokensTool(sessions))
    register(ForkSessionTool(sessions))
    register(SetSessionSpendCapTool(sessions))
    register(SetToolEnabledTool(sessions))
    register(SwitchProjectTool(sessions, projects, bus = bus, agentStates = agentStates))
    register(RevertSessionTool(sessions, projects, bus))
    register(SessionActionTool(sessions))
    register(ReadPartTool(sessions))
}

/**
 * Media import / frame extraction / bundle consolidation / asset
 * relinking. The `proxyGenerator` parameter is platform-injected:
 * JVM hosts (CLI / Desktop / Server) pass `FfmpegProxyGenerator()`;
 * Android passes `Media3ProxyGenerator(...)`. Both implement the
 * common [ProxyGenerator] interface.
 */
fun ToolRegistry.registerMediaTools(
    engine: VideoEngine,
    projects: ProjectStore,
    bundleBlobWriter: BundleBlobWriter,
    proxyGenerator: ProxyGenerator,
) {
    register(ImportMediaTool(engine, projects, proxyGenerator = proxyGenerator))
    register(ExtractFrameTool(engine, projects, bundleBlobWriter))
    register(ConsolidateMediaIntoBundleTool(projects))
    register(RelinkAssetTool(projects))
}

/**
 * Timeline clip + track edit verbs, filter / LUT / subtitle / transition
 * ops, and the clip-level state-reset tools (`RevertTimelineTool`,
 * `ClearTimelineTool`).
 */
fun ToolRegistry.registerClipAndTrackTools(
    projects: ProjectStore,
    sessions: SessionStore,
) {
    register(ClipActionTool(projects))
    register(ReplaceClipTool(projects))
    register(SplitClipTool(projects))
    register(MoveClipTool(projects))
    register(SetClipSourceBindingTool(projects))
    register(TrimClipTool(projects))
    register(SetClipVolumeTool(projects))
    register(FadeAudioClipTool(projects))
    register(SetClipTransformTool(projects))
    register(FilterActionTool(projects))
    register(ApplyLutTool(projects))
    register(AddSubtitlesTool(projects))
    register(EditTextClipTool(projects))
    register(TransitionActionTool(projects))
    register(TrackActionTool(projects))
    register(DuplicateTrackTool(projects))
    register(ReorderTracksTool(projects))
    register(RevertTimelineTool(sessions, projects))
    register(ClearTimelineTool(projects))
}

/**
 * Project CRUD, export/render, snapshot / lockfile / cache GC,
 * validation, and fork/diff. `RegenerateStaleClipsTool` + `ForkProjectTool`
 * take the registry via `this` so they can look up `apply_filter` /
 * `generate_*` at dispatch time.
 */
fun ToolRegistry.registerProjectTools(
    projects: ProjectStore,
    engine: VideoEngine,
) {
    register(ExportTool(projects, engine))
    register(ExportDryRunTool(projects))
    register(CreateProjectTool(projects))
    register(OpenProjectTool(projects))
    register(CreateProjectFromTemplateTool(projects))
    register(ListProjectsTool(projects))
    register(GetProjectStateTool(projects))
    register(DeleteProjectTool(projects))
    register(RenameProjectTool(projects))
    register(FindStaleClipsTool(projects))
    register(ProjectQueryTool(projects))
    register(RemoveAssetTool(projects))
    register(SetOutputProfileTool(projects))
    register(ValidateProjectTool(projects))
    register(RegenerateStaleClipsTool(projects, this))
    register(ProjectMaintenanceActionTool(projects, engine))
    register(SetLockfileEntryPinnedTool(projects))
    register(SetClipAssetPinnedTool(projects))
    register(ProjectSnapshotActionTool(projects))
    register(ForkProjectTool(projects, this))
    register(DiffProjectsTool(projects))
    register(ExportProjectTool(projects))
    register(ImportProjectFromJsonTool(projects))
}

/**
 * Source-DAG tools: consolidated `source_query` + deep-inspect +
 * CRUD / fork / rename / body-update verbs.
 */
fun ToolRegistry.registerSourceNodeTools(projects: ProjectStore) {
    register(SourceQueryTool(projects))
    register(DescribeSourceNodeTool(projects))
    register(DiffSourceNodesTool(projects))
    register(RemoveSourceNodeTool(projects))
    register(ImportSourceNodeTool(projects))
    register(ExportSourceNodeTool(projects))
    register(AddSourceNodeTool(projects))
    register(ForkSourceNodeTool(projects))
    register(SetSourceNodeParentsTool(projects))
    register(RenameSourceNodeTool(projects))
    register(UpdateSourceNodeBodyTool(projects))
}

/**
 * Filesystem / shell / web — coding-agent surface. `WebSearchTool`
 * only registers when the Tavily search engine was wired (CLI / Server
 * need `TAVILY_API_KEY` in the env for that). Android deliberately
 * skips this group — phone UI doesn't want shell / fs / web tools.
 */
fun ToolRegistry.registerBuiltinFileTools(
    fileSystem: FileSystem,
    processRunner: ProcessRunner,
    httpClient: HttpClient,
    search: SearchEngine?,
) {
    register(ReadFileTool(fileSystem))
    register(WriteFileTool(fileSystem))
    register(EditTool(fileSystem))
    register(MultiEditTool(fileSystem))
    register(ListDirectoryTool(fileSystem))
    register(GlobTool(fileSystem))
    register(GrepTool(fileSystem))
    register(BashTool(processRunner))
    register(WebFetchTool(httpClient))
    search?.let { register(WebSearchTool(it)) }
}

/**
 * AIGC pipeline: generation, upscale, speech synthesis, transcription,
 * auto-subtitle, vision description. All conditional — each engine is
 * wired only when the provider API key was present in the env.
 * `CompareAigcCandidatesTool` + `ReplayLockfileTool` take the registry
 * via `this` so they can dispatch back to the conditionally-present
 * `generate_*` tools they were asked to compare.
 *
 * All AIGC tools persist into the project bundle via [bundleBlobWriter];
 * reference-asset resolution goes through `BundleMediaPathResolver`
 * inside each tool (see `core/platform/BundleMediaPathResolver.kt`).
 */
fun ToolRegistry.registerAigcTools(
    imageGen: ImageGenEngine?,
    videoGen: VideoGenEngine?,
    musicGen: MusicGenEngine?,
    upscale: UpscaleEngine?,
    tts: TtsEngine?,
    asr: AsrEngine?,
    vision: VisionEngine?,
    bundleBlobWriter: BundleBlobWriter,
    projects: ProjectStore,
) {
    imageGen?.let { register(GenerateImageTool(it, bundleBlobWriter, projects)) }
    videoGen?.let { register(GenerateVideoTool(it, bundleBlobWriter, projects)) }
    musicGen?.let { register(GenerateMusicTool(it, bundleBlobWriter, projects)) }
    upscale?.let { register(UpscaleAssetTool(it, bundleBlobWriter, projects)) }
    tts?.let { register(SynthesizeSpeechTool(it, bundleBlobWriter, projects)) }
    register(CompareAigcCandidatesTool(this))
    register(ReplayLockfileTool(this, projects))
    asr?.let {
        register(TranscribeAssetTool(it, projects))
        register(AutoSubtitleClipTool(it, projects))
    }
    vision?.let { register(DescribeAssetTool(it, projects)) }
}
