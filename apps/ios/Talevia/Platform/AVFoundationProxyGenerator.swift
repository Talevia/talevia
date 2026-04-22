import AVFoundation
import CoreGraphics
import CoreImage
import Foundation
import ImageIO
import TaleviaCore
import UniformTypeIdentifiers

/// iOS counterpart of `Media3ProxyGenerator` (Android) and
/// `FfmpegProxyGenerator` (JVM). VISION §5.3 parity — until this lands
/// the iOS app ran with `NoopProxyGenerator`, so a 4K import gave every
/// UI consumer nothing to decode except the full-res source. Every
/// thumbnail scrub hit the AVAssetImageGenerator full-frame path.
///
/// Strategy mirrors Android exactly (same mid-duration single-frame
/// thumbnail, same 320px target width, same JPEG-at-85 encoding):
///  - **Video** (asset has a `videoCodec`): seek to half the duration
///    via `AVAssetImageGenerator.copyCGImage(at:actualTime:)`, scale to
///    `THUMB_WIDTH` preserving aspect (Core Image's `CILanczosScaleTransform`),
///    JPEG-encode via `CGImageDestination` at `JPEG_QUALITY`, write to
///    `<proxyDir>/<assetId>/thumb.jpg`, return one `ProxyAsset(purpose=THUMBNAIL)`.
///  - **Image / audio-only**: skip. Still-image thumbnails + audio
///    waveforms can come in a follow-up (parity with FFmpeg's image
///    branch + the showwavespic audio-waveform branch); this first pass
///    matches Android's video-only coverage.
///
/// Protocol conformance uses SKIE's callback style, same as
/// `IosFileBlobWriter` / `AVFoundationVideoEngine`: we implement
/// `__generate(asset:completionHandler:)` and callers get an `async`
/// form auto-generated. Work is dispatched off-main via `Task.detached`
/// to keep the import path non-blocking.
///
/// Best-effort per the `ProxyGenerator` contract — every failure mode
/// (source file missing, codec unreadable, CGImage synth fails, disk
/// full) collapses to an empty list. The import itself still succeeds;
/// the UI fallback is "decode original", identical to the old noop
/// behaviour.
final class AVFoundationProxyGenerator: NSObject, ProxyGenerator {

    private let pathResolver: any MediaPathResolver
    private let proxyDir: URL

    init(pathResolver: any MediaPathResolver, proxyDir: URL) {
        self.pathResolver = pathResolver
        self.proxyDir = proxyDir
        super.init()
        try? FileManager.default.createDirectory(at: proxyDir, withIntermediateDirectories: true)
    }

    /// Default location: `<caches>/talevia-proxies`. Matches
    /// `IosFileBlobWriter.defaultRoot()`'s rationale — caches tier so
    /// the OS may evict under storage pressure, but proxies are
    /// regeneratable from the asset's source binding.
    static func defaultRoot() -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        return caches.appendingPathComponent("talevia-proxies", isDirectory: true)
    }

    // swiftlint:disable identifier_name
    func __generate(
        asset: MediaAsset,
        completionHandler: @escaping @Sendable ([ProxyAsset]?, (any Error)?) -> Void
    ) {
        // Only video gets a mid-frame thumbnail — stills / audio-only fall
        // through to the noop-equivalent empty list, matching Android.
        guard asset.metadata.videoCodec != nil else {
            completionHandler([], nil)
            return
        }

        // AVAsset-level source resolution. File is the only branch that's
        // thumbnailable on iOS without download / PHAsset resolution, same
        // constraint AVFoundationVideoEngine.__thumbnail enforces.
        let sourcePath: String
        switch onEnum(of: asset.source) {
        case .file(let file):
            sourcePath = file.path
        case .http, .platform:
            completionHandler([], nil)
            return
        }

        // Mid-duration in seconds. Android picks `duration/2` in ms; we use
        // the same midpoint via AVAsset's reported duration (fall back to
        // the Kotlin-side metadata if AVAsset hasn't materialised yet).
        let metaSeconds = IosBridgesKt.durationToSeconds(d: asset.metadata.duration)
        guard metaSeconds > 0 else {
            completionHandler([], nil)
            return
        }
        let midSeconds = metaSeconds / 2.0

        let assetId = asset.id.value
        let outDir = proxyDir.appendingPathComponent(assetId, isDirectory: true)
        let thumbURL = outDir.appendingPathComponent("thumb.jpg")

        let url = URL(fileURLWithPath: sourcePath)
        guard FileManager.default.fileExists(atPath: sourcePath) else {
            completionHandler([], nil)
            return
        }

        Task.detached {
            do {
                try FileManager.default.createDirectory(
                    at: outDir,
                    withIntermediateDirectories: true
                )

                let avAsset = AVURLAsset(url: url)
                let generator = AVAssetImageGenerator(asset: avAsset)
                generator.appliesPreferredTrackTransform = true
                // Thumbnail is a coarse preview — any nearby keyframe is
                // fine, and `zero` tolerance can push the generator into
                // decoding long GOPs just to hit an exact ns. Matches
                // Android's `OPTION_CLOSEST_SYNC`.
                generator.requestedTimeToleranceBefore = CMTime(seconds: 0.5, preferredTimescale: 600)
                generator.requestedTimeToleranceAfter = CMTime(seconds: 0.5, preferredTimescale: 600)

                let requestTime = CMTime(seconds: midSeconds, preferredTimescale: 600)
                var actualTime = CMTime.zero
                let cgImage = try generator.copyCGImage(at: requestTime, actualTime: &actualTime)

                let scaled = Self.scaleToWidth(cgImage, targetWidth: Self.THUMB_WIDTH) ?? cgImage
                guard Self.writeJPEG(scaled, to: thumbURL, quality: Self.JPEG_QUALITY) else {
                    completionHandler([], nil)
                    return
                }

                let resolution = Resolution(width: Int32(scaled.width), height: Int32(scaled.height))
                let proxy = ProxyAsset(
                    source: MediaSource.File(path: thumbURL.path),
                    purpose: ProxyPurpose.thumbnail,
                    resolution: resolution
                )
                completionHandler([proxy], nil)
            } catch {
                // Best-effort: never surface exceptions to import caller.
                completionHandler([], nil)
            }
        }
    }
    // swiftlint:enable identifier_name

    /// Lanczos scale preserving aspect. Returns nil on CI failure —
    /// caller falls back to the unscaled `CGImage`, which still gets
    /// JPEG-encoded; a full-res-ish thumbnail beats none.
    private static func scaleToWidth(_ source: CGImage, targetWidth: Int) -> CGImage? {
        if source.width == targetWidth { return source }
        let aspect = Double(source.height) / Double(source.width)
        let height = max(Int(Double(targetWidth) * aspect), 1)

        let ciContext = CIContext(options: nil)
        let ciImage = CIImage(cgImage: source)
        let scale = Double(targetWidth) / Double(source.width)
        guard let filter = CIFilter(name: "CILanczosScaleTransform") else { return nil }
        filter.setValue(ciImage, forKey: kCIInputImageKey)
        filter.setValue(scale, forKey: kCIInputScaleKey)
        filter.setValue(1.0, forKey: kCIInputAspectRatioKey)
        guard let output = filter.outputImage else { return nil }
        let extent = CGRect(x: 0, y: 0, width: targetWidth, height: height)
        return ciContext.createCGImage(output, from: extent)
    }

    private static func writeJPEG(_ image: CGImage, to url: URL, quality: Double) -> Bool {
        guard let destination = CGImageDestinationCreateWithURL(
            url as CFURL,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else { return false }
        let props: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: quality]
        CGImageDestinationAddImage(destination, image, props as CFDictionary)
        return CGImageDestinationFinalize(destination)
    }

    // Match Android's Media3ProxyGenerator constants verbatim — keeps
    // the three engines' proxy sizes / qualities in lockstep for the
    // "proxies are the same shape regardless of platform" assertion.
    private static let THUMB_WIDTH: Int = 320
    private static let JPEG_QUALITY: Double = 0.85
}
