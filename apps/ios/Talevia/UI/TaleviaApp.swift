import SwiftUI
import TaleviaCore

@main
struct TaleviaApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

// MARK: - Root

/// Three-panel editor shell matching the Desktop (`apps/desktop/Main.kt`) and
/// Android (`MainActivity.AppRoot`) layouts: Chat, Timeline, Source as tabs.
/// All state lives in Core — this view is a thin SwiftUI projection of
/// `ProjectStore` / `SessionStore` / `EventBus` observed through SKIE-generated
/// async APIs.
struct RootView: View {
    private let container = AppContainer.shared

    @State private var projectId: Any? = nil
    @State private var bootstrapError: String? = nil

    var body: some View {
        TabView {
            if let pid = projectId {
                ChatPanel(container: container, projectId: pid)
                    .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right") }
                TimelinePanel(container: container, projectId: pid)
                    .tabItem { Label("Timeline", systemImage: "film") }
                SourcePanel(container: container, projectId: pid)
                    .tabItem { Label("Source", systemImage: "person.crop.rectangle.stack") }
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                    Text(bootstrapError ?? "Loading project…").font(.footnote)
                }
                .tabItem { Label("Talevia", systemImage: "hourglass") }
            }
        }
        .task { await bootstrap() }
    }

    @MainActor
    private func bootstrap() async {
        do {
            // Bridge defined in IosBridges.kt: finds-or-creates the default
            // project row and returns the ProjectId. Swift doesn't need to
            // reconstruct the full Project defaults tree.
            let pid = try await bootstrapDefaultProject(projects: container.projects)
            self.projectId = pid
        } catch {
            self.bootstrapError = "Bootstrap failed: \(error.localizedDescription)"
        }
    }
}

// MARK: - Chat panel

/// Minimal chat surface: scrollable message list + a prompt field. Live updates
/// arrive from `EventBus.PartUpdated`; the send button routes into `runAgent`
/// (see `IosBridges.kt`). When no provider key is configured we show a banner
/// instead of letting the user type into a dead loop.
struct ChatPanel: View {
    let container: AppContainer
    let projectId: Any

    @State private var sessionId: Any? = nil
    @State private var messages: [ChatLine] = []
    @State private var prompt: String = ""
    @State private var busy: Bool = false
    @State private var error: String? = nil
    /// Tracks the in-progress streaming part so PartDelta can append rather
    /// than spawn a new line per token — matches OpenCode's delta semantics.
    @State private var streamingPartId: String? = nil

    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                if container.defaultProvider == nil {
                    Text("No provider API key. Set ANTHROPIC_API_KEY or OPENAI_API_KEY in the Xcode scheme's environment.")
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .padding(.horizontal)
                }
                if let err = error {
                    Text(err).font(.caption).foregroundStyle(.red).padding(.horizontal)
                }
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(messages) { line in
                            Text(line.text)
                                .font(.system(.footnote, design: .monospaced))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                    }.padding(.horizontal)
                }
                HStack {
                    TextField("Ask the agent…", text: $prompt)
                        .textFieldStyle(.roundedBorder)
                        .disabled(busy || container.defaultProvider == nil)
                    if busy {
                        Button(action: { Task { await stop() } }) {
                            Text("Stop")
                        }
                        .buttonStyle(.bordered)
                        .tint(.red)
                    } else {
                        Button(action: { Task { await send() } }) {
                            Text("Send")
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(prompt.trimmingCharacters(in: .whitespaces).isEmpty || container.defaultProvider == nil)
                    }
                }.padding([.horizontal, .bottom])
            }
            .navigationTitle("Chat")
            .task { await bootstrap() }
        }
    }

    @MainActor
    private func bootstrap() async {
        do {
            let session = try await bootstrapChatSession(
                sessions: container.sessions,
                projectId: projectId,
                clock: ClockSystem.shared
            )
            self.sessionId = session.id
            // Rehydrate busy state in case a previous run is still in-flight
            // (can happen when the shell is re-created while the Agent keeps
            // its background coroutine alive — Android doesn't have this
            // problem because MainActivity gets torn down with the Agent).
            if let provider = container.defaultProvider,
               let agent = container.agent(for: provider.id) {
                let running = try? await isAgentRunning(agent: agent, sessionId: session.id)
                self.busy = running?.boolValue ?? false
            }
            await subscribe(sessionId: session.id)
        } catch {
            self.error = "Session bootstrap failed: \(error.localizedDescription)"
        }
    }

    /// Consume every SessionEvent for this chat: `PartUpdated` finalises a
    /// part, `PartDelta` streams into it, `SessionCancelled` / `AgentRunFailed`
    /// flip state machine transitions. All driven by `sessionEvents` in
    /// IosBridges.kt — SKIE turns the `Flow<BusEvent.SessionEvent>` into a
    /// Swift `AsyncSequence`.
    @MainActor
    private func subscribe(sessionId sid: Any) async {
        for await event in sessionEvents(bus: container.bus, sessionId: sid) {
            switch onEnum(of: event) {
            case .partUpdated(let e):
                handlePartUpdated(e.part)
            case .partDelta(let e):
                handlePartDelta(partId: partIdRaw(id: e.partId), field: e.field, delta: e.delta)
            case .sessionCancelled:
                self.busy = false
            case .agentRunFailed(let e):
                append(.error("agent: \(e.message)"))
                self.busy = false
            default:
                break
            }
        }
    }

    @MainActor
    private func handlePartUpdated(_ part: Part) {
        switch onEnum(of: part) {
        case .text(let t):
            let partIdStr = partIdRaw(id: t.id)
            // If we were streaming this text part via PartDelta, the final
            // update just replaces the streaming buffer. Otherwise create a
            // new line.
            if streamingPartId == partIdStr, let idx = messages.lastIndex(where: { $0.partId == partIdStr }) {
                messages[idx] = .assistantFinal(text: String(t.text.prefix(400)), partId: partIdStr)
                streamingPartId = nil
            } else if streamingPartId != partIdStr {
                append(.assistantFinal(text: String(t.text.prefix(400)), partId: partIdStr))
            }
        case .tool(let tool):
            append(.tool("[\(tool.toolId)] \(String(describing: type(of: tool.state)))"))
        default:
            break
        }
    }

    @MainActor
    private func handlePartDelta(partId: String, field: String, delta: String) {
        guard field == "text" else { return }
        if streamingPartId == partId, let idx = messages.lastIndex(where: { $0.partId == partId }) {
            messages[idx] = messages[idx].appending(delta)
        } else {
            streamingPartId = partId
            append(.assistantStreaming(delta, partId: partId))
        }
    }

    @MainActor
    private func send() async {
        let text = prompt.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, !busy else { return }
        guard let provider = container.defaultProvider,
              let agent = container.agent(for: provider.id),
              let sid = sessionId else {
            self.error = "No agent configured."
            return
        }
        self.prompt = ""
        self.busy = true
        self.error = nil
        append(.user(text))
        do {
            // runAgent (IosBridges.kt) wraps Agent.run + default ModelRef +
            // permission rules. Returns when the agent emits its final
            // assistant message; live updates stream through subscribe().
            _ = try await runAgent(
                agent: agent,
                sessionId: sid,
                text: text,
                providerId: provider.id,
                modelId: defaultModelId(for: provider.id)
            )
        } catch {
            append(.error("agent: \(error.localizedDescription)"))
            self.error = error.localizedDescription
        }
        self.busy = false
    }

    @MainActor
    private func stop() async {
        guard let provider = container.defaultProvider,
              let agent = container.agent(for: provider.id),
              let sid = sessionId else { return }
        // cancelAgent (IosBridges.kt) calls Agent.cancel — the running
        // coroutine throws CancellationException and the Agent finalises
        // the in-flight assistant message with FinishReason.CANCELLED.
        // The `SessionCancelled` event on the bus flips `busy` back.
        _ = try? await cancelAgent(agent: agent, sessionId: sid)
    }

    private func append(_ line: ChatLine) {
        messages.append(line)
    }

    /// Sensible default model per provider; tools can pick different models
    /// via their own RunInput, the UI only owns one entry point.
    private func defaultModelId(for providerId: String) -> String {
        switch providerId {
        case "anthropic": return "claude-opus-4-7"
        case "openai":    return "gpt-4o"
        default:          return "claude-opus-4-7"
        }
    }
}

private struct ChatLine: Identifiable {
    let id: UUID
    let text: String
    /// Bound to the Part.id for streaming assistant messages; `nil` for
    /// user lines and tool markers.
    let partId: String?

    init(text: String, partId: String? = nil) {
        self.id = UUID()
        self.text = text
        self.partId = partId
    }

    static func user(_ s: String) -> ChatLine                           { ChatLine(text: "you: \(s)") }
    static func assistantFinal(text: String, partId: String) -> ChatLine { ChatLine(text: "ai: \(text)", partId: partId) }
    static func assistantStreaming(_ s: String, partId: String) -> ChatLine { ChatLine(text: "ai: \(s)", partId: partId) }
    static func tool(_ s: String) -> ChatLine                           { ChatLine(text: "tool \(s)") }
    static func error(_ s: String) -> ChatLine                          { ChatLine(text: "! \(s)") }

    func appending(_ delta: String) -> ChatLine {
        ChatLine(text: text + delta, partId: partId)
    }
}

// MARK: - Timeline panel

/// Read-only view of `Project.timeline.tracks`. Refreshes on every
/// `PartUpdated` (tool dispatches always emit part updates, so this is a
/// coarse but correct trigger — matches the Android/Desktop pattern).
struct TimelinePanel: View {
    let container: AppContainer
    let projectId: Any

    @State private var rows: [TimelineRow] = []
    @State private var empty: Bool = true

    var body: some View {
        NavigationStack {
            List {
                if empty {
                    Text("No tracks yet. Ask the agent to add clips.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(rows) { row in
                        if row.isHeader {
                            Text(row.text).font(.subheadline).bold()
                        } else {
                            Text(row.text).font(.system(.caption, design: .monospaced))
                        }
                    }
                }
            }
            .navigationTitle("Timeline")
            .task { await subscribe() }
        }
    }

    @MainActor
    private func subscribe() async {
        await reload()
        for await _ in anyPartUpdates(bus: container.bus) {
            await reload()
        }
    }

    @MainActor
    private func reload() async {
        do {
            let project = try await container.projects.get(id: projectId)
            let tracks = project?.timeline.tracks ?? []
            var out: [TimelineRow] = []
            for track in tracks {
                switch onEnum(of: track) {
                case .video(let v):
                    out.append(.header("▶ Video · \(v.clips.count) clips"))
                    out.append(contentsOf: v.clips.map { clipRow($0) })
                case .audio(let a):
                    out.append(.header("♪ Audio · \(a.clips.count) clips"))
                    out.append(contentsOf: a.clips.map { clipRow($0) })
                case .subtitle(let s):
                    out.append(.header("𝐓 Subtitle · \(s.clips.count) clips"))
                    out.append(contentsOf: s.clips.map { clipRow($0) })
                case .effect(let e):
                    out.append(.header("✦ Effect · \(e.clips.count) clips"))
                    out.append(contentsOf: e.clips.map { clipRow($0) })
                }
            }
            self.rows = out
            self.empty = tracks.isEmpty
        } catch {
            self.rows = []
            self.empty = true
        }
    }

    private func clipRow(_ clip: Clip) -> TimelineRow {
        let id = clipIdRaw(id: clip.id).prefix(8)
        let startSec = durationToSeconds(d: clip.timeRange.start)
        let endSec = durationToSeconds(d: clip.timeRange.end)
        return .line(String(format: "  %@ · %.2f–%.2fs", String(id), startSec, endSec))
    }
}

private struct TimelineRow: Identifiable {
    let id = UUID()
    let text: String
    let isHeader: Bool

    static func header(_ s: String) -> TimelineRow { TimelineRow(text: s, isHeader: true) }
    static func line(_ s: String) -> TimelineRow   { TimelineRow(text: s, isHeader: false) }
}

// MARK: - Source panel

/// Read-only projection of `Project.source.nodes`. Same bus-driven refresh
/// pattern as the timeline panel.
struct SourcePanel: View {
    let container: AppContainer
    let projectId: Any

    @State private var lines: [SourceLine] = []
    @State private var empty: Bool = true

    var body: some View {
        NavigationStack {
            List {
                if empty {
                    Text("No source nodes yet. Ask the agent to define characters or style.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(lines) { line in
                        Text(line.text).font(.system(.caption, design: .monospaced))
                    }
                }
            }
            .navigationTitle("Source")
            .task { await subscribe() }
        }
    }

    @MainActor
    private func subscribe() async {
        await reload()
        for await _ in anyPartUpdates(bus: container.bus) {
            await reload()
        }
    }

    @MainActor
    private func reload() async {
        do {
            let project = try await container.projects.get(id: projectId)
            let nodes = project?.source.nodes ?? []
            self.lines = nodes.map { n in
                let idStr = sourceNodeIdRaw(id: n.id)
                return SourceLine(text: "[\(n.kind)] \(idStr.prefix(8))")
            }
            self.empty = nodes.isEmpty
        } catch {
            self.lines = []
            self.empty = true
        }
    }
}

private struct SourceLine: Identifiable {
    let id = UUID()
    let text: String
}
