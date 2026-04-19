import SwiftUI
import TaleviaCore

@main
struct TaleviaApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// Minimal status-screen scaffold. The composition root (`AppContainer.shared`)
/// builds the Kotlin graph — SQLDelight driver, stores, `ToolRegistry`, and the
/// AVFoundation-backed `VideoEngine` — so exercising it here also serves as a
/// symbol-reachability smoke check for the Kotlin/Native framework.
///
/// A real editor UI lands later; this screen intentionally stays one-view so
/// the framework link can be verified at launch without a full UX surface.
struct ContentView: View {
    @State private var coreStatus: String = "loading..."

    var body: some View {
        VStack(spacing: 16) {
            Text("Talevia").font(.largeTitle).bold()
            Text("iOS scaffold — framework linked").font(.caption)
            Divider()
            Text(coreStatus).font(.system(.caption, design: .monospaced))
        }
        .padding()
        .task { @MainActor in
            let container = AppContainer.shared
            let ruleCount = DefaultPermissionRuleset.shared.rules.count
            let toolCount = container.tools.all().count
            // Reference the concrete engine type so the linker keeps the
            // symbol live in Debug builds with dead-code stripping.
            let engineType = String(describing: type(of: container.engine))
            coreStatus = "core ok · rules=\(ruleCount) · tools=\(toolCount) · engine=\(engineType)"
        }
    }
}
