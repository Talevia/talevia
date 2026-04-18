import SwiftUI
import TaleviaCore

@main
struct TaleviaApp: App {
    @StateObject private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            ContentView(container: container)
        }
    }
}

/// Composition root mirroring `apps/desktop/AppContainer.kt`. The Kotlin core stays
/// platform-agnostic; this is where the iOS-native pieces (AVFoundation engine,
/// in-memory media storage for the M3 demo) get plugged in.
@MainActor
final class AppContainer: ObservableObject {
    let engine = AVFoundationVideoEngine()
    let media = InMemoryMediaStorage()
    let permissions = AllowAllPermissionService()
    let bus = EventBus(extraBufferCapacity: 256)

    // SQLDelight Native driver (configured in Kotlin via NativeSqliteDriver).
    // For M3 we stand up an in-memory SQLite via the bridge factory exposed
    // by core's iOS source set.
    lazy var driver = TaleviaDatabaseFactory().createInMemoryDriver()
    lazy var db = TaleviaDb(driver: driver)
    lazy var sessions = SqlDelightSessionStore(db: db, bus: bus, json: JsonConfig.shared.default_)
    lazy var projects: ProjectStore = SqlDelightProjectStore(db: db, clock: ClockSystem.shared, json: JsonConfig.shared.default_)

    lazy var registry: ToolRegistry = {
        let r = ToolRegistry()
        r.register(tool: ImportMediaTool(storage: media, engine: engine))
        r.register(tool: AddClipTool(store: projects, media: media))
        r.register(tool: SplitClipTool(store: projects))
        r.register(tool: ExportTool(store: projects, engine: engine, clock: ClockSystem.shared))
        return r
    }()
}

struct ContentView: View {
    let container: AppContainer
    @State private var log: [String] = ["TaleviaCore framework loaded"]
    @State private var importPath: String = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Talevia (M3 scaffold)").font(.title2).bold()

                HStack {
                    TextField("Import path", text: $importPath)
                        .textFieldStyle(.roundedBorder)
                    Button("Import") {
                        Task { await runImport() }
                    }
                    .disabled(importPath.isEmpty)
                }

                Divider()

                Text("Activity").font(.headline)
                ScrollView {
                    VStack(alignment: .leading) {
                        ForEach(log.indices, id: \.self) { i in
                            Text(log[i]).font(.system(.caption, design: .monospaced))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding()
            .navigationTitle("Talevia")
        }
    }

    private func runImport() async {
        let path = importPath
        importPath = ""
        do {
            let asset = try await container.media.import(
                source: MediaSource.File(path: path),
                explicitId: AssetId(value: path),
                probe: { src in try await container.engine.probe(source: src) }
            )
            log.append("imported \(asset.id.value) duration=\(asset.metadata.duration)")
        } catch {
            log.append("import failed: \(error.localizedDescription)")
        }
    }
}
