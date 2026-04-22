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
    let projects: SqlDelightProjectStore
    /// [InMemoryMediaStorage] already implements [MediaPathResolver], so we
    /// pass the same instance to the engine — keeping architecture rule #4
    /// (all asset paths route through a resolver) on iOS.
    let media: InMemoryMediaStorage
    let engine: AVFoundationVideoEngine
    let blobWriter: IosFileBlobWriter
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
        let factory = TaleviaDatabaseFactory()
        self.driver = factory.createInMemoryDriver()
        self.db = TaleviaDbCompanion.shared.invoke(driver: self.driver)
        self.bus = EventBus(extraBufferCapacity: 0)
        self.agentStates = AgentRunStateTrackerCompanion.shared.withSupervisor(bus: self.bus)
        let clock = ClockSystem.shared
        let json = JsonConfig.shared.default
        self.sessions = SqlDelightSessionStore(db: self.db, bus: self.bus, clock: clock, json: json)
        self.projects = SqlDelightProjectStore(db: self.db, clock: clock, json: json, bus: self.bus)
        self.media = InMemoryMediaStorage()
        self.engine = AVFoundationVideoEngine(resolver: self.media)
        self.blobWriter = IosFileBlobWriter(rootDir: IosFileBlobWriter.defaultRoot())
        self.permissions = DefaultPermissionService(bus: self.bus)

        let registry = ToolRegistry()
        registry.register(tool: ListToolsTool(registry: registry))
        registry.register(tool: EstimateTokensTool())
        registry.register(tool: TodoWriteTool(clock: clock))
        registry.register(tool: CompareAigcCandidatesTool(registry: registry))
        registry.register(tool: SessionQueryTool(sessions: self.sessions, agentStates: self.agentStates, projects: self.projects))
        registry.register(tool: ExportSessionTool(sessions: self.sessions))
        registry.register(tool: EstimateSessionTokensTool(sessions: self.sessions))
        registry.register(tool: ForkSessionTool(sessions: self.sessions))
        registry.register(tool: RenameSessionTool(sessions: self.sessions, clock: clock))
        registry.register(tool: SwitchProjectTool(sessions: self.sessions, projects: self.projects, clock: clock, bus: self.bus))
        registry.register(tool: RevertSessionTool(sessions: self.sessions, projects: self.projects, bus: self.bus))
        registry.register(tool: ArchiveSessionTool(sessions: self.sessions, clock: clock))
        registry.register(tool: UnarchiveSessionTool(sessions: self.sessions, clock: clock))
        registry.register(tool: DeleteSessionTool(sessions: self.sessions))
        registry.register(tool: ReadPartTool(sessions: self.sessions))
        registry.register(tool: ImportMediaTool(storage: self.media, engine: self.engine))
        registry.register(tool: ExtractFrameTool(engine: self.engine, storage: self.media, blobWriter: self.blobWriter))
        registry.register(tool: AddClipTool(store: self.projects, media: self.media))
        registry.register(tool: ReplaceClipTool(store: self.projects, media: self.media))
        registry.register(tool: SplitClipTool(store: self.projects))
        registry.register(tool: RemoveClipTool(store: self.projects))
        registry.register(tool: MoveClipTool(store: self.projects))
        registry.register(tool: SetClipSourceBindingTool(store: self.projects))
        registry.register(tool: DuplicateClipTool(store: self.projects))
        registry.register(tool: TrimClipTool(store: self.projects, media: self.media))
        registry.register(tool: SetClipVolumeTool(store: self.projects))
        registry.register(tool: FadeAudioClipTool(store: self.projects))
        registry.register(tool: SetClipTransformTool(store: self.projects))
        registry.register(tool: ExportTool(store: self.projects, engine: self.engine, clock: clock))
        registry.register(tool: ExportDryRunTool(store: self.projects))
        registry.register(tool: ApplyFilterTool(store: self.projects))
        registry.register(tool: RemoveFilterTool(store: self.projects))
        registry.register(tool: ApplyLutTool(store: self.projects, media: self.media))
        registry.register(tool: AddSubtitlesTool(store: self.projects))
        registry.register(tool: EditTextClipTool(store: self.projects))
        registry.register(tool: AddTransitionTool(store: self.projects))
        registry.register(tool: RemoveTransitionTool(store: self.projects))
        registry.register(tool: AddTrackTool(store: self.projects))
        registry.register(tool: DuplicateTrackTool(store: self.projects))
        registry.register(tool: RemoveTrackTool(store: self.projects))
        registry.register(tool: ReorderTracksTool(store: self.projects))
        registry.register(tool: RevertTimelineTool(sessions: self.sessions, projects: self.projects))
        registry.register(tool: ClearTimelineTool(store: self.projects))
        registry.register(tool: CreateProjectTool(projects: self.projects))
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
        registry.register(tool: PruneLockfileTool(projects: self.projects))
        registry.register(tool: GcLockfileTool(projects: self.projects, clock: clock))
        registry.register(tool: SetLockfileEntryPinnedTool(projects: self.projects))
        registry.register(tool: SetClipAssetPinnedTool(projects: self.projects))
        registry.register(tool: SaveProjectSnapshotTool(projects: self.projects, clock: clock))
        registry.register(tool: ListProjectSnapshotsTool(projects: self.projects))
        registry.register(tool: RestoreProjectSnapshotTool(projects: self.projects))
        registry.register(tool: DeleteProjectSnapshotTool(projects: self.projects))
        registry.register(tool: ForkProjectTool(projects: self.projects))
        registry.register(tool: DiffProjectsTool(projects: self.projects))
        registry.register(tool: ExportProjectTool(projects: self.projects))
        registry.register(tool: ImportProjectFromJsonTool(projects: self.projects))
        registry.register(tool: SetCharacterRefTool(projects: self.projects))
        registry.register(tool: SetStyleBibleTool(projects: self.projects))
        registry.register(tool: SetBrandPaletteTool(projects: self.projects))
        registry.register(tool: SourceQueryTool(projects: self.projects))
        registry.register(tool: DescribeSourceNodeTool(projects: self.projects))
        registry.register(tool: DiffSourceNodesTool(projects: self.projects))
        registry.register(tool: RemoveSourceNodeTool(projects: self.projects))
        registry.register(tool: ImportSourceNodeTool(projects: self.projects))
        registry.register(tool: ExportSourceNodeTool(projects: self.projects))
        registry.register(tool: AddSourceNodeTool(projects: self.projects))
        registry.register(tool: ForkSourceNodeTool(projects: self.projects))
        registry.register(tool: SetSourceNodeParentsTool(projects: self.projects))
        registry.register(tool: RenameSourceNodeTool(projects: self.projects))
        registry.register(tool: UpdateSourceNodeBodyTool(projects: self.projects))
        self.tools = registry

        self.httpClient = createIosHttpClient()
        self.providers = buildIosProviderRegistry(httpClient: self.httpClient)

        // Provider-dependent tools land after providers is initialised.
        registry.register(tool: ListProvidersTool(providers: self.providers))
        registry.register(tool: ListProviderModelsTool(providers: self.providers))
        registry.register(tool: CompactSessionTool(providers: self.providers, sessions: self.sessions, bus: self.bus))
    }
}
