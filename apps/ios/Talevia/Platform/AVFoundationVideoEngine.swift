import AVFoundation
import Foundation
import TaleviaCore

/// iOS native `VideoEngine` backed by AVFoundation. This commit is the wiring
/// stub — the concrete `probe`, `thumbnail`, and `render` implementations land
/// in follow-up commits. The init signature mirrors the Android and FFmpeg
/// engines: a `MediaPathResolver` is required so asset paths route through
/// the resolver per architecture rule #4.
///
/// ## Protocol conformance notes
/// SKIE's Swift protocol for `VideoEngine` exposes Kotlin `suspend` functions
/// through two surfaces at once:
///  1. A `__probe(source:completionHandler:)` / `__thumbnail(...)` requirement
///     that the conforming type must implement (the callback-style API).
///  2. A public `probe(source:) async throws` extension method that auto-wraps
///     the callback form — this is what *callers* use.
///
/// So the implementer writes the callback-style `__probe` method, and callers
/// get the async form for free. `render` returns a Kotlin `Flow` wrapped in
/// SKIE's `SkieSwiftFlow<any RenderProgress>` via `SwiftRenderFlowAdapter`
/// from iosMain (added in a later commit).
final class AVFoundationVideoEngine: NSObject, VideoEngine {
    private let resolver: any MediaPathResolver

    init(resolver: any MediaPathResolver) {
        self.resolver = resolver
        super.init()
    }

    // swiftlint:disable identifier_name
    func __probe(
        source: MediaSource,
        completionHandler: @escaping @Sendable (MediaMetadata?, (any Error)?) -> Void
    ) {
        completionHandler(nil, NSError(domain: "AVFoundationVideoEngine", code: -1, userInfo: [
            NSLocalizedDescriptionKey: "probe: not yet implemented — lands in commit B2",
        ]))
    }

    func __thumbnail(
        asset: Any,
        source: MediaSource,
        time: Int64,
        completionHandler: @escaping @Sendable (KotlinByteArray?, (any Error)?) -> Void
    ) {
        completionHandler(nil, NSError(domain: "AVFoundationVideoEngine", code: -1, userInfo: [
            NSLocalizedDescriptionKey: "thumbnail: not yet implemented — lands in commit B3",
        ]))
    }
    // swiftlint:enable identifier_name

    func render(timeline: Timeline, output: OutputSpec) -> SkieSwiftFlow<any RenderProgress> {
        // Real implementation lands in commit B4; return a flow that emits a
        // single Failed event so callers that hit this stub see a graceful
        // error rather than a crash.
        let jobId = UUID().uuidString
        let adapter = SwiftRenderFlowAdapter()
        _ = adapter.tryEmit(event: RenderProgressFailed(
            jobId: jobId,
            message: "render: not yet implemented — lands in commit B4"
        ))
        adapter.close()
        return adapter.asFlow()
    }
}
