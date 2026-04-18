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

/// **Status (M3 / workaround pass):** compile-verification scaffold. The app
/// links against `TaleviaCore`; full composition root (SQLDelight driver
/// wrapping, tool registry wiring) needs iOS-side iteration — the value-class
/// branded ID types (`SessionId`, `ProjectId`, `AssetId`, ...) don't export
/// cleanly through ObjC so Swift needs a small typealias shim before it can
/// construct them. See `docs/IOS_INTEGRATION.md` §5.
struct ContentView: View {
    @State private var coreStatus: String = "loading..."

    var body: some View {
        VStack(spacing: 16) {
            Text("Talevia").font(.largeTitle).bold()
            Text("M3 iOS scaffold — framework linked").font(.caption)
            Divider()
            Text(coreStatus).font(.system(.caption, design: .monospaced))
        }
        .padding()
        .onAppear {
            // Exercise one core symbol that DOES round-trip cleanly (no value classes).
            let ruleCount = DefaultPermissionRuleset.shared.rules.count
            coreStatus = "core reachable · default permission rules: \(ruleCount)"
        }
    }
}
