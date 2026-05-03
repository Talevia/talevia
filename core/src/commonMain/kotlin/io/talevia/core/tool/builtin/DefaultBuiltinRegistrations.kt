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
import io.talevia.core.tool.builtin.aigc.AigcGenerateTool
import io.talevia.core.tool.builtin.aigc.AigcImageGenerator
import io.talevia.core.tool.builtin.aigc.AigcMusicGenerator
import io.talevia.core.tool.builtin.aigc.AigcSpeechGenerator
import io.talevia.core.tool.builtin.aigc.AigcVideoGenerator
import io.talevia.core.tool.builtin.aigc.CompareAigcCandidatesTool
import io.talevia.core.tool.builtin.aigc.ReplayLockfileTool
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
import io.talevia.core.tool.builtin.project.DiffProjectsTool
import io.talevia.core.tool.builtin.project.ExportProjectTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.ImportProjectFromJsonTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.ProjectActionDispatcherTool
import io.talevia.core.tool.builtin.project.ProjectLifecycleActionTool
import io.talevia.core.tool.builtin.project.ProjectMaintenanceActionTool
import io.talevia.core.tool.builtin.project.ProjectPinActionTool
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.ProjectSnapshotActionTool
import io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool
import io.talevia.core.tool.builtin.session.ReadPartTool
import io.talevia.core.tool.builtin.session.SessionActionTool
import io.talevia.core.tool.builtin.session.SessionQueryTool
import io.talevia.core.tool.builtin.session.SwitchProjectTool
import io.talevia.core.tool.builtin.shell.BashTool
import io.talevia.core.tool.builtin.source.DiffSourceNodesTool
import io.talevia.core.tool.builtin.source.ExportSourceNodeTool
import io.talevia.core.tool.builtin.source.SourceNodeActionTool
import io.talevia.core.tool.builtin.source.SourceQueryTool
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.AutoSubtitleClipTool
import io.talevia.core.tool.builtin.video.ClearTimelineTool
import io.talevia.core.tool.builtin.video.ClipActionTool
import io.talevia.core.tool.builtin.video.ConsolidateMediaIntoBundleTool
import io.talevia.core.tool.builtin.video.ExportDryRunTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ExtractFrameTool
import io.talevia.core.tool.builtin.video.FilterActionTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.RelinkAssetTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import io.talevia.core.tool.builtin.video.TimelineActionTool
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
 * top (e.g. Server registers `ProviderQueryTool` and re-registers
 * `SessionActionTool` with `providers=` wired in for the
 * `action="compact"` path in a second pass because both depend on
 * `ProviderRegistry`, which itself is built from the same container).
 *
 * `ProviderQueryTool` is intentionally NOT in here — it depends on
 * `ProviderRegistry`, which is built from the same container in a
 * second pass. `SessionActionTool` is registered first-pass without
 * a `ProviderRegistry`; AppContainers re-register it with full deps
 * (including `providers=`) in the second pass to enable the
 * `action="compact"` path. `ToolRegistry.register` is overwrite-by-id
 * so the second registration replaces the first.
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
    permissionHistory: io.talevia.core.permission.PermissionHistoryRecorder? = null,
    permissionRulesPersistence: io.talevia.core.permission.PermissionRulesPersistence =
        io.talevia.core.permission.PermissionRulesPersistence.Noop,
    busTrace: io.talevia.core.bus.BusEventTraceRecorder? = null,
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
            permissionHistory = permissionHistory,
            busTrace = busTrace,
        ),
    )
    register(SwitchProjectTool(sessions, projects, bus = bus, agentStates = agentStates))
    register(
        SessionActionTool(
            sessions = sessions,
            permissionRulesPersistence = permissionRulesPersistence,
            projects = projects,
            busTrace = busTrace,
            bus = bus,
        ),
    )
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
    register(FilterActionTool(projects))
    register(AddSubtitlesTool(projects))
    register(TimelineActionTool(projects))
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
    sessions: SessionStore? = null,
) {
    register(ExportTool(projects, engine))
    register(ExportDryRunTool(projects))
    // Cycle 61 introduced the `project_action(kind=...)` dispatcher
    // alongside the 4 underlying `project_*_action` tools. Cycle 63
    // phase 2 unregisters the originals — only the dispatcher is now
    // LLM-facing. Underlying tool classes are still constructed (the
    // dispatcher needs them as routing targets) but their `register(...)`
    // calls are removed, mirroring AIGC arc cycle 27. Test code that
    // exercises the underlying tools directly (`ProjectXActionTool(store)
    // .execute(...)`) keeps working — direct construction is independent
    // of registry membership.
    val lifecycle = ProjectLifecycleActionTool(projects, sessions = sessions)
    val maintenance = ProjectMaintenanceActionTool(projects, engine)
    val pin = ProjectPinActionTool(projects)
    val snapshot = ProjectSnapshotActionTool(projects)
    register(ProjectActionDispatcherTool(lifecycle, maintenance, pin, snapshot))
    register(ListProjectsTool(projects))
    register(ProjectQueryTool(projects))
    register(RegenerateStaleClipsTool(projects, this))
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
    register(DiffSourceNodesTool(projects))
    register(ExportSourceNodeTool(projects))
    register(SourceNodeActionTool(projects))
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
    val imageTool = imageGen?.let { AigcImageGenerator(it, bundleBlobWriter, projects) }
    val videoTool = videoGen?.let { AigcVideoGenerator(it, bundleBlobWriter, projects) }
    val musicTool = musicGen?.let { AigcMusicGenerator(it, bundleBlobWriter, projects) }
    val speechTool = tts?.let { AigcSpeechGenerator(it, bundleBlobWriter, projects) }
    upscale?.let { register(UpscaleAssetTool(it, bundleBlobWriter, projects)) }
    // `aigc-tool-consolidation-phase2-unregister-originals` (cycle 27):
    // the 4 generators are no longer registered as standalone tools —
    // the LLM-facing surface is a single `aigc_generate(kind=...)`
    // dispatcher. Underlying instances still exist (constructed above);
    // the dispatcher holds them and routes per `kind`. Lockfile entries
    // continue to stamp `toolId="generate_image"` etc. via the inner
    // tool — phase 3 will internalise the helpers and unify the stamp.
    // [ReplayLockfileTool] returns a clear error when asked to replay
    // an entry whose toolId is no longer in the registry (the 4
    // legacy ids surface as "post-phase-2; not replayable" rather
    // than a generic "tool not found").
    //
    // `debt-aigc-tool-consolidation-phase3c-1-collapse-adapter-layer`
    // (cycle 39): the underlying `Generate*Tool` / `AigcSpeechGenerator`
    // classes now implement their per-kind `*AigcGenerator` interface
    // directly (in addition to `Tool<I, O>`), so the `ToolBacked*Generator`
    // adapter layer phase 3a introduced is redundant — pass the classes
    // straight to the dispatcher. Phase 3c-2 will drop `Tool<I, O>` from
    // the underlying classes once the e2e tests that still register them
    // by-id move to a JVM-test-only legacy shim.
    register(AigcGenerateTool(image = imageTool, video = videoTool, music = musicTool, speech = speechTool))
    register(CompareAigcCandidatesTool(this))
    register(ReplayLockfileTool(this, projects))
    asr?.let {
        register(TranscribeAssetTool(it, projects))
        register(AutoSubtitleClipTool(it, projects))
    }
    vision?.let { register(DescribeAssetTool(it, projects)) }
}
