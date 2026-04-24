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
        self.agentStates = AgentRunStateTrackerCompanion.shared.withSupervisor(bus: self.bus)
        let clock = ClockSystem.shared
        let json = JsonConfig.shared.default
        self.sessions = SqlDelightSessionStore(db: self.db, bus: self.bus, clock: clock, json: json)

        // File-bundle ProjectStore rooted at <Documents>/projects/, recents
        // at <Documents>/recents.json. Bypasses the SqlDelight ProjectStore
        // entirely on iOS (it's gone in the file-bundle migration).
        let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let projectsHome = docsDir.appendingPathComponent("projects").path
        let recentsPath = docsDir.appendingPathComponent("recents.json").path
        self.recentsRegistry = newRecentsRegistry(path: recentsPath)
        self.projects = newFileProjectStore(
            registry: self.recentsRegistry,
            projectsHome: projectsHome,
            bus: self.bus
        )
        self.bundleBlobWriter = newFileBundleBlobWriter(projects: self.projects)

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

        let registry = ToolRegistry()
        registry.register(tool: ListToolsTool(registry: registry))
        registry.register(tool: EstimateTokensTool())
        registry.register(tool: TodoWriteTool(clock: clock))
        registry.register(tool: DraftPlanTool(clock: clock))
        registry.register(tool: ExecutePlanTool(registry: registry, sessions: self.sessions, clock: clock))
        registry.register(tool: CompareAigcCandidatesTool(registry: registry))
        registry.register(tool: ReplayLockfileTool(registry: registry, projects: self.projects))
        registry.register(tool: SessionQueryTool(sessions: self.sessions, agentStates: self.agentStates, projects: self.projects, toolRegistry: registry))
        registry.register(tool: ExportSessionTool(sessions: self.sessions))
        registry.register(tool: EstimateSessionTokensTool(sessions: self.sessions))
        registry.register(tool: ForkSessionTool(sessions: self.sessions))
        registry.register(tool: SessionActionTool(sessions: self.sessions, clock: clock))
        registry.register(tool: SetSessionSpendCapTool(sessions: self.sessions, clock: clock))
        registry.register(tool: SetToolEnabledTool(sessions: self.sessions, clock: clock))
        registry.register(tool: SwitchProjectTool(sessions: self.sessions, projects: self.projects, clock: clock, bus: self.bus))
        registry.register(tool: RevertSessionTool(sessions: self.sessions, projects: self.projects, bus: self.bus))
        registry.register(tool: ReadPartTool(sessions: self.sessions))
        registry.register(tool: ImportMediaTool(
            engine: self.engine,
            projects: self.projects,
            proxyGenerator: self.proxyGenerator
        ))
        registry.register(tool: ExtractFrameTool(engine: self.engine, projects: self.projects, bundleBlobWriter: self.bundleBlobWriter))
        registry.register(tool: ConsolidateMediaIntoBundleTool(projects: self.projects))
        registry.register(tool: RelinkAssetTool(projects: self.projects))
        registry.register(tool: AddClipTool(store: self.projects))
        registry.register(tool: ReplaceClipTool(store: self.projects))
        registry.register(tool: SplitClipTool(store: self.projects))
        registry.register(tool: RemoveClipTool(store: self.projects))
        registry.register(tool: MoveClipTool(store: self.projects))
        registry.register(tool: SetClipSourceBindingTool(store: self.projects))
        registry.register(tool: DuplicateClipTool(store: self.projects))
        registry.register(tool: TrimClipTool(store: self.projects))
        registry.register(tool: SetClipVolumeTool(store: self.projects))
        registry.register(tool: FadeAudioClipTool(store: self.projects))
        registry.register(tool: SetClipTransformTool(store: self.projects))
        registry.register(tool: ExportTool(store: self.projects, engine: self.engine, clock: clock))
        registry.register(tool: ExportDryRunTool(store: self.projects))
        registry.register(tool: FilterActionTool(store: self.projects))
        registry.register(tool: ApplyLutTool(store: self.projects))
        registry.register(tool: AddSubtitlesTool(store: self.projects))
        registry.register(tool: EditTextClipTool(store: self.projects))
        registry.register(tool: TransitionActionTool(store: self.projects))
        registry.register(tool: AddTrackTool(store: self.projects))
        registry.register(tool: DuplicateTrackTool(store: self.projects))
        registry.register(tool: RemoveTrackTool(store: self.projects))
        registry.register(tool: ReorderTracksTool(store: self.projects))
        registry.register(tool: RevertTimelineTool(sessions: self.sessions, projects: self.projects))
        registry.register(tool: ClearTimelineTool(store: self.projects))
        registry.register(tool: CreateProjectTool(projects: self.projects))
        registry.register(tool: OpenProjectTool(projects: self.projects))
        registry.register(tool: CreateProjectFromTemplateTool(projects: self.projects))
        registry.register(tool: ListProjectsTool(projects: self.projects))
        registry.register(tool: GetProjectStateTool(projects: self.projects))
        registry.register(tool: ProjectQueryTool(projects: self.projects))
        registry.register(tool: RemoveAssetTool(projects: self.projects))
        registry.register(tool: SetOutputProfileTool(projects: self.projects))
        registry.register(tool: ValidateProjectTool(projects: self.projects))
        registry.register(tool: DeleteProjectTool(projects: self.projects))
        registry.register(tool: RenameProjectTool(projects: self.projects))
        registry.register(tool: FindStaleClipsTool(projects: self.projects))
        registry.register(tool: ProjectMaintenanceActionTool(projects: self.projects, engine: self.engine, clock: clock))
        registry.register(tool: SetLockfileEntryPinnedTool(projects: self.projects))
        registry.register(tool: SetClipAssetPinnedTool(projects: self.projects))
        registry.register(tool: ProjectSnapshotActionTool(projects: self.projects, clock: clock))
        registry.register(tool: ForkProjectTool(projects: self.projects, registry: registry))
        registry.register(tool: DiffProjectsTool(projects: self.projects))
        registry.register(tool: ExportProjectTool(projects: self.projects))
        registry.register(tool: ImportProjectFromJsonTool(projects: self.projects))
        registry.register(tool: SourceQueryTool(projects: self.projects))
        registry.register(tool: DescribeSourceNodeTool(projects: self.projects))
        registry.register(tool: DiffSourceNodesTool(projects: self.projects))
        registry.register(tool: ImportSourceNodeTool(projects: self.projects))
        registry.register(tool: ExportSourceNodeTool(projects: self.projects))
        registry.register(tool: SourceNodeActionTool(projects: self.projects))
        registry.register(tool: SetSourceNodeParentsTool(projects: self.projects))
        registry.register(tool: RenameSourceNodeTool(projects: self.projects))
        registry.register(tool: UpdateSourceNodeBodyTool(projects: self.projects))
        self.tools = registry

        self.httpClient = createIosHttpClient()
        self.providers = buildIosProviderRegistry(httpClient: self.httpClient)

        // Provider-dependent tools land after providers is initialised.
        registry.register(tool: ProviderQueryTool(providers: self.providers))
        registry.register(tool: CompactSessionTool(providers: self.providers, sessions: self.sessions, bus: self.bus))
    }
}

/// Stub resolver that errors if called — the real resolver is built per-render
/// by ExportTool / the AIGC tools via `BundleMediaPathResolver(project, bundleRoot)`.
/// This instance exists only to satisfy the engine constructor's non-null
/// `resolver` parameter.
private final class FailingMediaPathResolver: NSObject, MediaPathResolver {
    // swiftlint:disable identifier_name
    func __resolve(
        assetId: AssetId,
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
