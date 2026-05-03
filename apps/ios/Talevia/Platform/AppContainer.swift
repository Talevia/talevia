import Foundation
import TaleviaCore

/// Composition root for the iOS app. Mirrors `AndroidAppContainer.kt` and
/// `apps/desktop/AppContainer.kt`: holds the singleton graph (SQLite driver,
/// stores, the AVFoundation-backed VideoEngine, the ToolRegistry).
///
/// The graph is built lazily on first access of `shared`; construction touches
/// the Kotlin/Native runtime so all wiring happens on the main thread to match
/// SKIE's dispatcher expectations. Keep construction cheap — no blocking I/O.
@MainActor
final class AppContainer {
    static let shared = AppContainer()

    let driver: RuntimeSqlDriver
    let db: any TaleviaDb
    let bus: EventBus
    let agentStates: AgentRunStateTracker
    let warmupStats: ProviderWarmupStats
    let permissionHistory: PermissionHistoryRecorder
    let rateLimitHistory: RateLimitHistoryRecorder
    let busTrace: BusEventTraceRecorder
    let sessions: SqlDelightSessionStore
    /// File-bundle [ProjectStore]. Bundles default to
    /// `<Documents>/projects/...`; the per-machine recents catalog lives at
    /// `<Documents>/recents.json`. Replaces the SQL-backed store the iOS app
    /// used during M3.
    let recentsRegistry: RecentsRegistry
    let projects: FileProjectStore
    let bundleBlobWriter: FileBundleBlobWriter
    let engine: AVFoundationVideoEngine
    let proxyGenerator: AVFoundationProxyGenerator
    let permissions: DefaultPermissionService
    let tools: ToolRegistry

    /// HTTP client shared by the provider registry. Kept alive for the
    /// container's lifetime — Ktor pools connections internally.
    let httpClient: Ktor_client_coreHttpClient
    let providers: ProviderRegistry

    /// One Agent per provider id, memoized so subsequent `Send` taps reuse the
    /// same Compactor / background scope. Mirrors `AndroidAppContainer.agentFor`.
    private var agents: [String: Agent] = [:]

    /// Nil when neither `ANTHROPIC_API_KEY` nor `OPENAI_API_KEY` is present in
    /// the process environment — the SwiftUI shell shows a helper banner in
    /// that case instead of a dead chat box.
    var defaultProvider: (any LlmProvider)? { providers.default }

    func agent(for providerId: String) -> Agent? {
        if let existing = agents[providerId] { return existing }
        guard let provider = providers.get(providerId: providerId) else { return nil }
        let fallbacks = providers.all().filter { $0.id != provider.id }
        let agent = doNewIosAgent(
            provider: provider,
            tools: tools,
            sessions: sessions,
            permissions: permissions,
            bus: bus,
            fallbackProviders: fallbacks
        )
        agents[providerId] = agent
        return agent
    }

    private init() {
        // Sessions DB: switch to a file-backed driver so iOS sessions /
        // messages / parts survive app restarts (parity with desktop /
        // Android).
        let factory = TaleviaDatabaseFactory()
        self.driver = factory.createPersistentDriver(name: "talevia.db")
        self.db = TaleviaDbCompanion.shared.invoke(driver: self.driver)
        self.bus = EventBus(extraBufferCapacity: 0)
        // SKIE bridges class Companions as nested types — `XCompanion` (top-
        // level) stopped resolving in cycle ~145; access via `X.companion`
        // accessor instead. Interface Companions (TaleviaDbCompanion above)
        // stay top-level because Swift doesn't allow nested types on
        // protocols.
        self.agentStates = AgentRunStateTracker.companion.withSupervisor(bus: self.bus)
        self.warmupStats = ProviderWarmupStats.companion.withSupervisor(bus: self.bus)
        self.rateLimitHistory = RateLimitHistoryRecorder.companion.withSupervisor(bus: self.bus)
        let clock = ClockSystem.shared
        let json = JsonConfig.shared.default
        self.sessions = SqlDelightSessionStore(
            db: self.db,
            bus: self.bus,
            clock: clock,
            json: json,
            // SKIE doesn't translate Kotlin default parameters into Swift
            // initializer defaults — the Kotlin `maxMessages: Int =
            // DEFAULT_MAX_MESSAGES` ctor param shows up as required from
            // Swift. Pass the same default explicitly. (See cycle-149
            // ENGINEERING_NOTES "SKIE bridging quirks".)
            maxMessages: SqlDelightSessionStore.companion.DEFAULT_MAX_MESSAGES
        )
        self.permissionHistory = PermissionHistoryRecorder.companion.withSupervisor(bus: self.bus, store: self.sessions)
        self.busTrace = BusEventTraceRecorder.companion.withSupervisor(bus: self.bus)

        // File-bundle ProjectStore rooted at <Documents>/projects/, recents
        // at <Documents>/recents.json. Bypasses the SqlDelight ProjectStore
        // entirely on iOS (it's gone in the file-bundle migration).
        let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let projectsHome = docsDir.appendingPathComponent("projects").path
        let recentsPath = docsDir.appendingPathComponent("recents.json").path
        // Top-level Kotlin factory functions in `IosBridges.kt` are bridged
        // as `IosBridgesKt.doNew*` (SKIE prefixes `new` with `do` because
        // Swift's `new` is a reserved keyword in some grammar contexts).
        self.recentsRegistry = IosBridgesKt.doNewRecentsRegistry(path: recentsPath)
        self.projects = IosBridgesKt.doNewFileProjectStore(
            registry: self.recentsRegistry,
            projectsHome: projectsHome,
            bus: self.bus
        )
        self.bundleBlobWriter = IosBridgesKt.doNewFileBundleBlobWriter(projects: self.projects)

        // The engine takes a resolver for source-clip path resolution, but
        // ExportTool now passes a per-render BundleMediaPathResolver via
        // render(resolver:). The constructor-time resolver is therefore
        // unreachable on the happy path; this stub yells if anything ever
        // falls through to it.
        self.engine = AVFoundationVideoEngine(resolver: FailingMediaPathResolver())
        self.proxyGenerator = AVFoundationProxyGenerator(
            proxyDir: AVFoundationProxyGenerator.defaultRoot()
        )
        self.permissions = DefaultPermissionService(bus: self.bus)

        // SKIE doesn't translate Kotlin `= default` parameters into Swift
        // initializer defaults — every Kotlin ctor param shows up as
        // required in Swift, so each register call below must pass an
        // explicit value (typically `nil` for `T?` defaults, or the
        // matching companion-singleton for non-null defaults).
        let permissionRulesNoop = PermissionRulesPersistenceCompanion.shared.Noop
        let systemFs = OkioFileSystem.companion.SYSTEM
        let registry = ToolRegistry()
        registry.register(tool: ListToolsTool(registry: registry, metrics: nil))
        registry.register(tool: EstimateTokensTool())
        registry.register(tool: TodoWriteTool(clock: clock))
        registry.register(tool: DraftPlanTool(clock: clock))
        registry.register(tool: ExecutePlanTool(registry: registry, sessions: self.sessions, clock: clock))
        registry.register(tool: CompareAigcCandidatesTool(registry: registry))
        registry.register(tool: ReplayLockfileTool(registry: registry, projects: self.projects))
        registry.register(tool: SessionQueryTool(sessions: self.sessions, agentStates: self.agentStates, projects: self.projects, toolRegistry: registry, fallbackTracker: nil, permissionHistory: self.permissionHistory, busTrace: self.busTrace))
        // First-pass registration without `providers` — the ProviderRegistry
        // is built FROM this same container in the second pass, so
        // `action="compact"` is wired below at the second-pass re-register.
        // `bus` is required for `action="revert"` (publishes
        // BusEvent.SessionReverted); compact's bus needs land in the
        // second pass too.
        registry.register(tool: SessionActionTool(sessions: self.sessions, clock: clock, permissionRulesPersistence: permissionRulesNoop, projects: self.projects, busTrace: self.busTrace, bus: self.bus, providers: nil))
        registry.register(tool: SwitchProjectTool(sessions: self.sessions, projects: self.projects, clock: clock, bus: self.bus, agentStates: self.agentStates))
        registry.register(tool: ReadPartTool(sessions: self.sessions))
        registry.register(tool: ImportMediaTool(
            engine: self.engine,
            projects: self.projects,
            clock: clock,
            proxyGenerator: self.proxyGenerator,
            fs: systemFs,
            bundleBlobWriter: self.bundleBlobWriter,
            autoInBundleThresholdBytes: 50 * 1024 * 1024
        ))
        registry.register(tool: ExtractFrameTool(engine: self.engine, projects: self.projects, bundleBlobWriter: self.bundleBlobWriter))
        registry.register(tool: ConsolidateMediaIntoBundleTool(projects: self.projects, fs: systemFs, clock: clock, bundleBlobWriter: self.bundleBlobWriter))
        registry.register(tool: RelinkAssetTool(projects: self.projects, clock: clock))
        registry.register(tool: ClipActionTool(store: self.projects))
        // `ReplaceClipTool` / `FadeAudioClipTool` folded into
        // `clip_action(action="replace_asset")` / `clip_action(action="fade_audio")`.
        registry.register(tool: ClipSetActionTool(store: self.projects))
        registry.register(tool: ExportTool(store: self.projects, engine: self.engine, clock: clock))
        registry.register(tool: ExportDryRunTool(store: self.projects))
        registry.register(tool: FilterActionTool(store: self.projects))
        // `ApplyLutTool` folded into `filter_action(action="apply_lut")` (cycle 153).
        registry.register(tool: AddSubtitlesTool(store: self.projects))
        // `EditTextClipTool` folded into `clip_action(action="edit_text")` (cycle 152).
        registry.register(tool: TransitionActionTool(store: self.projects))
        // `AddTrackTool` / `RemoveTrackTool` (folded earlier) and
        // `DuplicateTrackTool` / `ReorderTracksTool` (cycle 151) all live
        // inside `TrackActionTool` now — `track_action(action="add"|
        // "remove"|"duplicate"|"reorder")`.
        registry.register(tool: TrackActionTool(store: self.projects))
        registry.register(tool: RevertTimelineTool(sessions: self.sessions, projects: self.projects))
        registry.register(tool: ClearTimelineTool(store: self.projects))
        // `create_from_template` is now `project_action(action="create_from_template")`;
        // `state` / `validation` / `stale_clips` are now `project_query(select=…)`.
        let lifecycle = ProjectLifecycleActionTool(projects: self.projects, sessions: self.sessions, clock: clock)
        let maintenance = ProjectMaintenanceActionTool(projects: self.projects, engine: self.engine, clock: clock)
        let pin = ProjectPinActionTool(projects: self.projects)
        let snapshot = ProjectSnapshotActionTool(projects: self.projects, clock: clock)
        registry.register(tool: lifecycle)
        registry.register(tool: maintenance)
        registry.register(tool: pin)
        registry.register(tool: snapshot)
        // Cycle 61: kind-discriminated `project_action` dispatcher
        // alongside the four underlying tools (phase 1a-2 impl).
        registry.register(tool: ProjectActionDispatcherTool(lifecycle: lifecycle, maintenance: maintenance, pin: pin, snapshot: snapshot))
        registry.register(tool: ListProjectsTool(projects: self.projects))
        registry.register(tool: ProjectQueryTool(projects: self.projects, clock: clock))
        registry.register(tool: ForkProjectTool(projects: self.projects, registry: registry))
        registry.register(tool: DiffProjectsTool(projects: self.projects))
        registry.register(tool: ExportProjectTool(projects: self.projects))
        registry.register(tool: ImportProjectFromJsonTool(projects: self.projects))
        // `describe` is now `source_query(select="describe")`;
        // `import` is now `source_node_action(action="import")`.
        registry.register(tool: SourceQueryTool(projects: self.projects))
        registry.register(tool: DiffSourceNodesTool(projects: self.projects))
        registry.register(tool: ExportSourceNodeTool(projects: self.projects))
        registry.register(tool: SourceNodeActionTool(projects: self.projects, clock: clock))
        self.tools = registry

        self.httpClient = createIosHttpClient()
        self.providers = buildIosProviderRegistry(httpClient: self.httpClient)

        // Provider-dependent tools land after providers is initialised.
        // iOS doesn't wire AIGC engines today — snapshot publishes
        // all-false rows so the agent sees missingEnvVar names rather
        // than an empty table that would be ambiguous with "not wired".
        let engineReadiness = EngineReadinessSnapshotKt.buildEngineReadinessSnapshot(
            imageGen: nil, videoGen: nil, musicGen: nil,
            tts: nil, asr: nil, vision: nil, upscale: nil, search: nil
        )
        registry.register(tool: ProviderQueryTool(
            providers: self.providers,
            warmupStats: self.warmupStats,
            projects: self.projects,
            rateLimitHistory: self.rateLimitHistory,
            engineReadiness: engineReadiness
        ))
        // Re-register SessionActionTool with `providers` wired in so
        // action="compact" dispatches against the live ProviderRegistry.
        // The first-pass registration above can't pass providers (the
        // registry is built from this same container in the second pass).
        // `ToolRegistry.register` is overwrite-by-id so this replaces
        // the first-pass registration. Mirrors the cycle-147 pattern in
        // the four Kotlin AppContainers (CLI / Desktop / Server / Android).
        registry.register(tool: SessionActionTool(
            sessions: self.sessions,
            clock: clock,
            permissionRulesPersistence: permissionRulesNoop,
            projects: self.projects,
            busTrace: self.busTrace,
            bus: self.bus,
            providers: self.providers
        ))

        // Eager-warm LLM providers so first AIGC dispatch doesn't pay
        // cold-start latency. Best-effort; failures swallowed (logged).
        ProviderWarmupKickoffKt.kickoffEagerProviderWarmupWithSupervisor(
            providers: self.providers,
            bus: self.bus,
            clock: ClockSystem.shared
        )
    }
}

/// Stub resolver that errors if called — the real resolver is built per-render
/// by ExportTool / the AIGC tools via `BundleMediaPathResolver(project, bundleRoot)`.
/// This instance exists only to satisfy the engine constructor's non-null
/// `resolver` parameter.
private final class FailingMediaPathResolver: NSObject, MediaPathResolver {
    // swiftlint:disable identifier_name
    // `AssetId` is a Kotlin `@JvmInline value class` — SKIE erases it to `id`
    // (Swift `Any`) on the iOS bridge, so the protocol method's parameter
    // type must be `Any`, not the (no-longer-exposed) `AssetId`.
    func __resolve(
        assetId: Any,
        completionHandler: @escaping @Sendable (String?, (any Error)?) -> Void
    ) {
        completionHandler(nil, NSError(
            domain: "io.talevia",
            code: -1,
            userInfo: [NSLocalizedDescriptionKey:
                "call site must pass per-render BundleMediaPathResolver via render(resolver:)"]
        ))
    }
    // swiftlint:enable identifier_name
}
