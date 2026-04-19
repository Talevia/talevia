import AVFoundation
import CoreMedia
import Foundation
import TaleviaCore

/// One-line helper for the `NSError` shape the Kotlin side sees on failed
/// suspend calls. Keeps the concrete engine methods readable.
private func avfError(_ message: String) -> NSError {
    NSError(domain: "AVFoundationVideoEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: message])
}

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
        // `MediaSource` is a sealed Kotlin class; only `.File` is resolvable to
        // an AVURLAsset on iOS without platform-side download (HTTP) or
        // PHAsset resolution (Platform). Match the Android engine's surface —
        // non-file sources fail with a clear error instead of silently trying.
        let path: String
        switch onEnum(of: source) {
        case .file(let file):
            path = file.path
        case .http:
            completionHandler(nil, avfError("Http MediaSource not supported (download first)"))
            return
        case .platform(let platform):
            completionHandler(nil, avfError("Platform MediaSource (\(platform.scheme)) not resolvable in AVFoundationVideoEngine"))
            return
        }

        let url = URL(fileURLWithPath: path)
        let asset = AVURLAsset(url: url)

        Task {
            do {
                // `load(.duration, .tracks)` on iOS 16+ returns typed values
                // in one round-trip. Fall back to legacy accessors only if we
                // need to support iOS 15 — deployment target is 17.0 so the
                // async loader is fine.
                let (duration, tracks) = try await asset.load(.duration, .tracks)

                // Convert CMTime → kotlin.time.Duration nanoseconds. CMTime
                // with a zero/invalid timescale crashes `seconds`; guard.
                let durationSeconds: Double =
                    (duration.isValid && !duration.isIndefinite && duration.timescale != 0)
                    ? CMTimeGetSeconds(duration)
                    : 0
                let durationNanos = IosBridgesKt.durationOfSeconds(seconds: durationSeconds)

                // First video track wins for resolution / frame rate; if none,
                // these stay nil (audio-only assets).
                let videoTrack = tracks.first(where: { $0.mediaType == .video })
                var resolution: Resolution?
                var frameRate: FrameRate?
                if let videoTrack {
                    let (naturalSize, preferredTransform, nominalFrameRate) = try await videoTrack.load(
                        .naturalSize, .preferredTransform, .nominalFrameRate
                    )
                    // AVFoundation exposes "natural" (raw) size; for portrait
                    // footage we need to swap w/h if the preferred transform
                    // rotates 90°/270°. Media3 does the same on Android.
                    let t = preferredTransform
                    let isPortrait = (abs(t.a) < 0.01 && abs(t.d) < 0.01) &&
                                     (abs(t.b) >= 0.99 && abs(t.c) >= 0.99)
                    let width = isPortrait ? naturalSize.height : naturalSize.width
                    let height = isPortrait ? naturalSize.width : naturalSize.height
                    resolution = Resolution(width: Int32(width.rounded()), height: Int32(height.rounded()))
                    if nominalFrameRate > 0 {
                        frameRate = FrameRate(numerator: Int32(nominalFrameRate.rounded()), denominator: 1)
                    }
                }

                let audioTrack = tracks.first(where: { $0.mediaType == .audio })
                var sampleRate: KotlinInt?
                var channels: KotlinInt?
                if let audioTrack,
                   let desc = try? await audioTrack.load(.formatDescriptions).first {
                    // AudioStreamBasicDescription hands back the sample rate
                    // and channel count via CMAudioFormatDescription.
                    if let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc)?.pointee {
                        sampleRate = KotlinInt(int: Int32(asbd.mSampleRate))
                        channels = KotlinInt(int: Int32(asbd.mChannelsPerFrame))
                    }
                }

                let metadata = MediaMetadata(
                    duration: durationNanos,
                    resolution: resolution,
                    frameRate: frameRate,
                    videoCodec: nil,
                    audioCodec: nil,
                    sampleRate: sampleRate,
                    channels: channels,
                    bitrate: nil
                )
                completionHandler(metadata, nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    func __thumbnail(
        asset: Any,
        source: MediaSource,
        time: Int64,
        completionHandler: @escaping @Sendable (KotlinByteArray?, (any Error)?) -> Void
    ) {
        completionHandler(nil, avfError("thumbnail: not yet implemented — lands in commit B3"))
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
