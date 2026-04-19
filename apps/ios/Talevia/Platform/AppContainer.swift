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
    let sessions: SqlDelightSessionStore
    let projects: SqlDelightProjectStore
    /// [InMemoryMediaStorage] already implements [MediaPathResolver], so we
    /// pass the same instance to the engine — keeping architecture rule #4
    /// (all asset paths route through a resolver) on iOS.
    let media: InMemoryMediaStorage
    let engine: AVFoundationVideoEngine
    let permissions: DefaultPermissionService
    let tools: ToolRegistry

    private init() {
        let factory = TaleviaDatabaseFactory()
        self.driver = factory.createInMemoryDriver()
        self.db = TaleviaDbCompanion.shared.invoke(driver: self.driver)
        self.bus = EventBus(extraBufferCapacity: 0)
        let clock = ClockSystem.shared
        let json = JsonConfig.shared.default
        self.sessions = SqlDelightSessionStore(db: self.db, bus: self.bus, clock: clock, json: json)
        self.projects = SqlDelightProjectStore(db: self.db, clock: clock, json: json)
        self.media = InMemoryMediaStorage()
        self.engine = AVFoundationVideoEngine(resolver: self.media)
        self.permissions = DefaultPermissionService(bus: self.bus)

        let registry = ToolRegistry()
        registry.register(tool: ImportMediaTool(storage: self.media, engine: self.engine))
        registry.register(tool: AddClipTool(store: self.projects, media: self.media))
        registry.register(tool: ReplaceClipTool(store: self.projects, media: self.media))
        registry.register(tool: SplitClipTool(store: self.projects))
        registry.register(tool: RemoveClipTool(store: self.projects))
        registry.register(tool: MoveClipTool(store: self.projects))
        registry.register(tool: ExportTool(store: self.projects, engine: self.engine, clock: clock))
        registry.register(tool: ApplyFilterTool(store: self.projects))
        registry.register(tool: ApplyLutTool(store: self.projects, media: self.media))
        registry.register(tool: AddSubtitleTool(store: self.projects))
        registry.register(tool: AddSubtitlesTool(store: self.projects))
        registry.register(tool: AddTransitionTool(store: self.projects))
        registry.register(tool: RevertTimelineTool(sessions: self.sessions, projects: self.projects))
        registry.register(tool: CreateProjectTool(projects: self.projects))
        registry.register(tool: ListProjectsTool(projects: self.projects))
        registry.register(tool: GetProjectStateTool(projects: self.projects))
        registry.register(tool: DeleteProjectTool(projects: self.projects))
        registry.register(tool: FindStaleClipsTool(projects: self.projects))
        registry.register(tool: ListLockfileEntriesTool(projects: self.projects))
        registry.register(tool: SaveProjectSnapshotTool(projects: self.projects, clock: clock))
        registry.register(tool: ListProjectSnapshotsTool(projects: self.projects))
        registry.register(tool: RestoreProjectSnapshotTool(projects: self.projects))
        registry.register(tool: ForkProjectTool(projects: self.projects))
        registry.register(tool: DiffProjectsTool(projects: self.projects))
        registry.register(tool: DefineCharacterRefTool(projects: self.projects))
        registry.register(tool: DefineStyleBibleTool(projects: self.projects))
        registry.register(tool: DefineBrandPaletteTool(projects: self.projects))
        registry.register(tool: ListSourceNodesTool(projects: self.projects))
        registry.register(tool: RemoveSourceNodeTool(projects: self.projects))
        registry.register(tool: ImportSourceNodeTool(projects: self.projects))
        self.tools = registry
    }
}
