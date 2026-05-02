import AVFoundation
import CoreImage
import CoreMedia
import Foundation
import ImageIO
import QuartzCore
import TaleviaCore
import UIKit
import UniformTypeIdentifiers

/// Flat, Sendable snapshot of the filter chain + transition-fade envelope for
/// a single clip range on the timeline. Built from [IosVideoClipPlan] at
/// composition-build time and captured by the `applyingCIFiltersWithHandler`
/// closure — SKIE-bridged Kotlin types aren't Sendable, so we copy into pure-
/// Swift structs before crossing into the concurrent image-filter handler.
///
/// A clip appears here iff it has at least one filter **or** a non-zero
/// head/tail fade — either case activates the CIFilter handler path for the
/// frames that belong to this clip.
private struct ClipFilterRange: Sendable {
    let start: Double
    let end: Double
    let filters: [FilterSpec]
    /// Head fade length in seconds (`0` = no fade). Frames in `[start,
    /// start+headFade]` ramp alpha from 0 to 1 so the clip dips in from black.
    let headFade: Double
    /// Tail fade length in seconds (`0` = no fade). Frames in `[end-tailFade,
    /// end]` ramp alpha from 1 to 0 so the clip dips out to black.
    let tailFade: Double
}

private struct FilterSpec: Sendable {
    let name: String
    let params: [String: Double]
    /// Pre-resolved `CIColorCube` payload for `lut` filters. `nil` for
    /// every other filter. Pre-resolved on the setup path (outside the
    /// filter handler) so frame rendering doesn't block on disk I/O or
    /// the `.cube` parser.
    let lut: LutPayload?
}

private struct LutPayload: Sendable {
    let dimension: Int
    let data: Data
}

/// Map a Core [Filter] (by name + params) onto a [CIImage], returning the
/// filtered image. Unknown filters pass through unchanged. This mirrors the
/// Android `Media3VideoEngine.mapFilterToEffect` parity goal — what the
/// FFmpeg engine accepts, the native engines should also render when the
/// platform has a reasonable primitive.
///
/// Supported today:
///  - `brightness` → `CIColorControls.inputBrightness` (Core's `-1..1` maps
///    1:1 onto CI's brightness delta, centered at 0 = no change).
///  - `saturation` → `CIColorControls.inputSaturation` (Core's `0..1`
///    intensity with `0.5 = neutral` → CI's `0..2` multiplicative scale
///    centered at 1.0. Linear remap: `intensity * 2`).
///  - `blur`       → `CIGaussianBlur.inputRadius` (matches FFmpeg shape:
///    `sigma` verbatim, else `radius` on `0..1` mapped to `0..10` radius).
///  - `vignette`   → `CIVignette.inputIntensity` + `inputRadius`. iOS has a
///    built-in primitive here, so we render it even though Media3 still
///    no-ops vignette.
///
/// Not yet supported:
///  - `lut` — same `.cube` parser gap as Media3; `CIColorCube` takes a raw
///    cube data blob (not a .cube file), so a loader is still needed.
///    Intentional pass-through for now so export succeeds instead of
///    crashing.
private func applyCoreFilter(_ spec: FilterSpec, to image: CIImage) -> CIImage {
    switch spec.name.lowercased() {
    case "brightness":
        let raw = spec.params["intensity"] ?? spec.params["value"] ?? 0
        let v = max(-1.0, min(1.0, raw))
        let f = CIFilter(name: "CIColorControls")
        f?.setValue(image, forKey: kCIInputImageKey)
        f?.setValue(v, forKey: kCIInputBrightnessKey)
        return f?.outputImage ?? image
    case "contrast":
        // Core's apply_filter contrast intensity is 0..1 with 0.5 = neutral
        // (matches FFmpeg's `eq=contrast` 0..2 / Media3 `Contrast` -1..+1).
        // Remap to CI's multiplicative `inputContrast` where 1.0 = identity:
        // intensity 0.5 → 1.0, 1.0 → 2.0, 0.0 → 0.0.
        let raw = spec.params["intensity"] ?? spec.params["value"]
        let contrast: Double
        if let raw, spec.params["intensity"] != nil {
            contrast = max(0.0, min(2.0, raw * 2.0))
        } else {
            contrast = 1.0
        }
        let f = CIFilter(name: "CIColorControls")
        f?.setValue(image, forKey: kCIInputImageKey)
        f?.setValue(contrast, forKey: kCIInputContrastKey)
        return f?.outputImage ?? image
    case "saturation":
        let raw = spec.params["intensity"] ?? spec.params["value"]
        // Core's apply_filter semantics: 0.5 ≈ unchanged (matches FFmpeg's
        // 0..1 → 0..2 mapping). Remap to CI's multiplicative scale where
        // 1.0 = identity. intensity 0.5 → 1.0, 1.0 → 2.0, 0.0 → 0.0.
        let saturation: Double
        if let raw, spec.params["intensity"] != nil {
            saturation = max(0.0, min(2.0, raw * 2.0))
        } else {
            saturation = 1.0
        }
        let f = CIFilter(name: "CIColorControls")
        f?.setValue(image, forKey: kCIInputImageKey)
        f?.setValue(saturation, forKey: kCIInputSaturationKey)
        return f?.outputImage ?? image
    case "blur":
        // Match the FFmpeg engine's two-knob shape.
        let radius: Double
        if let sigma = spec.params["sigma"] {
            radius = max(0.0, min(50.0, sigma))
        } else if let r01 = spec.params["radius"] {
            radius = max(0.0, min(50.0, r01 * 10.0))
        } else {
            radius = 5.0
        }
        let f = CIFilter(name: "CIGaussianBlur")
        f?.setValue(image, forKey: kCIInputImageKey)
        f?.setValue(radius, forKey: kCIInputRadiusKey)
        // CIGaussianBlur grows the extent; clamp back to the source so the
        // compositor doesn't crop the edges inward on downstream composites.
        guard let blurred = f?.outputImage else { return image }
        return blurred.cropped(to: image.extent)
    case "vignette":
        let intensity = max(0.0, min(2.0, spec.params["intensity"] ?? 1.0))
        let radius = max(0.0, min(10.0, spec.params["radius"] ?? 1.0))
        let f = CIFilter(name: "CIVignette")
        f?.setValue(image, forKey: kCIInputImageKey)
        f?.setValue(intensity, forKey: kCIInputIntensityKey)
        f?.setValue(radius, forKey: kCIInputRadiusKey)
        return f?.outputImage ?? image
    case "lut":
        guard let lut = spec.lut else { return image }
        let f = CIFilter(name: "CIColorCube")
        f?.setValue(image, forKey: kCIInputImageKey)
        f?.setValue(NSNumber(value: lut.dimension), forKey: "inputCubeDimension")
        f?.setValue(lut.data, forKey: "inputCubeData")
        return f?.outputImage ?? image
    default:
        return image
    }
}

/// Compute the transition alpha at composition time `t` for a given clip's
/// fade envelope. Returns `1.0` when the frame is outside both fade windows
/// (so the caller can short-circuit and skip the dim pass). Ramps linearly
/// from 0 → 1 over the head window and 1 → 0 over the tail window — matches
/// the FFmpeg engine's `fade=t=in/out:c=black` shape.
private func transitionAlphaAt(_ t: Double, clip: ClipFilterRange) -> Double {
    if clip.headFade > 0 {
        let headEnd = clip.start + clip.headFade
        if t < headEnd {
            let progress = (t - clip.start) / clip.headFade
            return max(0.0, min(1.0, progress))
        }
    }
    if clip.tailFade > 0 {
        let tailStart = clip.end - clip.tailFade
        if t >= tailStart {
            let progress = (clip.end - t) / clip.tailFade
            return max(0.0, min(1.0, progress))
        }
    }
    return 1.0
}

/// Dim a CI image's RGB channels toward black by scalar `alpha`. `alpha = 1`
/// is a no-op (caller short-circuits). `alpha = 0` produces a pure black
/// frame. Uses `CIColorMatrix` with R/G/B vectors scaled by alpha and no
/// bias — this preserves the image's own alpha channel and avoids the
/// gray-wash that `CIColorControls.inputContrast` would introduce.
private func dimToBlack(_ image: CIImage, alpha: Double) -> CIImage {
    guard let f = CIFilter(name: "CIColorMatrix") else { return image }
    f.setValue(image, forKey: kCIInputImageKey)
    let a = CGFloat(alpha)
    f.setValue(CIVector(x: a, y: 0, z: 0, w: 0), forKey: "inputRVector")
    f.setValue(CIVector(x: 0, y: a, z: 0, w: 0), forKey: "inputGVector")
    f.setValue(CIVector(x: 0, y: 0, z: a, w: 0), forKey: "inputBVector")
    f.setValue(CIVector(x: 0, y: 0, z: 0, w: 1), forKey: "inputAVector")
    f.setValue(CIVector(x: 0, y: 0, z: 0, w: 0), forKey: "inputBiasVector")
    return f.outputImage ?? image
}

/// One-line helper for the `NSError` shape the Kotlin side sees on failed
/// suspend calls. Keeps the concrete engine methods readable.
private func avfError(_ message: String) -> NSError {
    NSError(domain: "AVFoundationVideoEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: message])
}

/// Parse a `#RRGGBB` or `#RRGGBBAA` hex color into a `UIColor`. Returns `nil`
/// for malformed inputs so the caller can fall back without crashing.
private func parseHexColor(_ hex: String) -> UIColor? {
    var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.hasPrefix("#") { s.removeFirst() }
    guard s.count == 6 || s.count == 8 else { return nil }
    var v: UInt64 = 0
    guard Scanner(string: s).scanHexInt64(&v) else { return nil }
    let r, g, b, a: CGFloat
    if s.count == 8 {
        r = CGFloat((v >> 24) & 0xFF) / 255.0
        g = CGFloat((v >> 16) & 0xFF) / 255.0
        b = CGFloat((v >> 8) & 0xFF) / 255.0
        a = CGFloat(v & 0xFF) / 255.0
    } else {
        r = CGFloat((v >> 16) & 0xFF) / 255.0
        g = CGFloat((v >> 8) & 0xFF) / 255.0
        b = CGFloat(v & 0xFF) / 255.0
        a = 1.0
    }
    return UIColor(red: r, green: g, blue: b, alpha: a)
}

/// Resolve a [TextStyle] family + bold/italic combo into a `UIFont`. Unknown
/// families fall back to the system font with the requested traits.
private func makeSubtitleFont(family: String, size: CGFloat, bold: Bool, italic: Bool) -> UIFont {
    if family.lowercased() != "system", let custom = UIFont(name: family, size: size) {
        var traits: UIFontDescriptor.SymbolicTraits = []
        if bold { traits.insert(.traitBold) }
        if italic { traits.insert(.traitItalic) }
        if !traits.isEmpty, let descriptor = custom.fontDescriptor.withSymbolicTraits(traits) {
            return UIFont(descriptor: descriptor, size: size)
        }
        return custom
    }
    let base = UIFont.systemFont(ofSize: size, weight: bold ? .bold : .regular)
    if italic, let descriptor = base.fontDescriptor.withSymbolicTraits(.traitItalic) {
        return UIFont(descriptor: descriptor, size: size)
    }
    return base
}

/// Build the `(parentLayer, videoLayer)` pair that `AVVideoCompositionCoreAnimationTool`
/// needs to composite subtitle text over the exported video frames.
///
/// - `parentLayer` has `isGeometryFlipped = true` so a child's `y` is measured
///   from the **bottom** of the frame (matching the FFmpeg engine's
///   `y = h - text_h - margin` convention and Apple's recommended setup for
///   animation tools on iOS).
/// - `videoLayer` occupies the full frame and is what the animation tool
///   uses as the source for post-processing.
/// - Each subtitle becomes a `CATextLayer` at bottom-center, gated by a
///   `CABasicAnimation` on `opacity` that runs from `startSeconds` to
///   `endSeconds` — the layer's model opacity stays at `0`, so the text is
///   only visible while the animation is active.
private func buildSubtitleLayers(
    subtitles: [IosSubtitlePlan],
    renderSize: CGSize
) -> (parent: CALayer, video: CALayer) {
    let parent = CALayer()
    parent.frame = CGRect(origin: .zero, size: renderSize)
    parent.isGeometryFlipped = true

    let video = CALayer()
    video.frame = CGRect(origin: .zero, size: renderSize)
    parent.addSublayer(video)

    // Bottom-margin scales with height (48 / 1080 ≈ 4.4%). Matches the FFmpeg
    // engine's MVP so captions land in the same spot across engines.
    let margin = max(16.0, Double(renderSize.height) * 48.0 / 1080.0)

    for (index, sub) in subtitles.enumerated() {
        let font = makeSubtitleFont(
            family: sub.fontFamily,
            size: CGFloat(sub.fontSize),
            bold: sub.bold,
            italic: sub.italic
        )
        var attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: (parseHexColor(sub.colorHex) ?? .white).cgColor,
        ]
        if let bgHex = sub.backgroundHex, let bg = parseHexColor(bgHex) {
            attrs[.backgroundColor] = bg.cgColor
        }
        let attributed = NSAttributedString(string: sub.text, attributes: attrs)

        let textLayer = CATextLayer()
        textLayer.string = attributed
        textLayer.alignmentMode = .center
        textLayer.isWrapped = true
        textLayer.truncationMode = .end
        textLayer.contentsScale = 2.0
        // Height = font size * ~1.5 (line height + descender). Width spans the
        // full frame so .center alignment resolves relative to the frame.
        let textHeight = CGFloat(sub.fontSize) * 1.5
        textLayer.frame = CGRect(
            x: 0,
            y: CGFloat(margin),
            width: renderSize.width,
            height: textHeight
        )
        // Keep the layer invisible by default; the opacity animation below
        // reveals it only during the subtitle's timeline window.
        textLayer.opacity = 0

        let anim = CABasicAnimation(keyPath: "opacity")
        anim.fromValue = 1
        anim.toValue = 1
        // `AVCoreAnimationBeginTimeAtZero` is the iOS sentinel for "start at
        // the very first frame" — a literal 0 is treated as "start time not
        // set" and the animation never fires.
        anim.beginTime = sub.startSeconds > 0 ? CFTimeInterval(sub.startSeconds) : AVCoreAnimationBeginTimeAtZero
        anim.duration = max(0.01, CFTimeInterval(sub.endSeconds - sub.startSeconds))
        anim.isRemovedOnCompletion = true
        anim.fillMode = .removed
        textLayer.add(anim, forKey: "subtitleOpacity_\(index)")

        parent.addSublayer(textLayer)
    }
    return (parent, video)
}

/// Pick an export preset from the requested output height. Mirrors the way
/// the FFmpeg engine picks h264 profiles by resolution — AVFoundation doesn't
/// let us specify codec + bitrate on `AVAssetExportSession` directly, so
/// preset is the only knob. OutputSpec.videoCodec/bitrate/audioBitrate are
/// ignored — TODO: a future refactor could swap in AVAssetWriter for
/// finer-grained control.
private func exportPreset(for height: Int32) -> String {
    switch height {
    case 1080...: return AVAssetExportPreset1920x1080
    case 720...:  return AVAssetExportPreset1280x720
    default:      return AVAssetExportPresetHighestQuality
    }
}

/// Run the full export pipeline. Kept as a free function so it can be
/// dispatched onto a detached `Task` without capturing the engine actor.
///
/// Flow: resolve each clip's asset path → build an `AVMutableComposition` with
/// one video + one audio track → insert each clip's source-range slice at its
/// timeline-start offset → export the composition through
/// `AVAssetExportSession`. Filter / transition / subtitle passes are no-ops in
/// this first cut, matching the Media3 engine's scope (see CLAUDE.md "Known
/// incomplete").
private func runExport(
    timeline: Timeline,
    output: OutputSpec,
    jobId: String,
    resolver: any MediaPathResolver,
    adapter: SwiftRenderFlowAdapter
) async throws {
    let plans: [IosVideoClipPlan] = timeline.toIosVideoPlan()
    if plans.isEmpty {
        _ = adapter.tryEmit(event: RenderProgressFailed(jobId: jobId, message: "no video clips to render"))
        adapter.close()
        return
    }

    let composition = AVMutableComposition()
    let videoTrack = composition.addMutableTrack(
        withMediaType: .video,
        preferredTrackID: kCMPersistentTrackID_Invalid
    )
    // Add an audio track lazily — only once we find a source clip that has
    // audio. AVAssetExportSession has been known to choke on empty
    // composition audio tracks with confusing error codes (we hit -16976
    // "OperationStopped" without this guard).
    var audioTrack: AVMutableCompositionTrack?

    for plan in plans {
        // Route every asset path through MediaPathResolver (architecture
        // rule #4). The resolver is suspend on the Kotlin side — SKIE
        // bridges it to async throws.
        let assetId = IosBridgesKt.assetId(value: plan.assetIdRaw)
        let path = try await resolver.resolve(assetId: assetId)
        let url = URL(fileURLWithPath: path)
        let sourceAsset = AVURLAsset(url: url)

        // Timescale of 600 is AVFoundation's canonical choice — it's divisible
        // by 24/25/30/60 fps so frame-accurate edits don't drift.
        let insertRange = CMTimeRange(
            start: CMTime(seconds: plan.sourceStartSeconds, preferredTimescale: 600),
            duration: CMTime(seconds: plan.sourceDurationSeconds, preferredTimescale: 600)
        )
        let timelineStart = CMTime(seconds: plan.timelineStartSeconds, preferredTimescale: 600)

        let tracks = try await sourceAsset.load(.tracks)
        if let srcVideo = tracks.first(where: { $0.mediaType == .video }) {
            try videoTrack?.insertTimeRange(insertRange, of: srcVideo, at: timelineStart)
        }
        if let srcAudio = tracks.first(where: { $0.mediaType == .audio }) {
            if audioTrack == nil {
                audioTrack = composition.addMutableTrack(
                    withMediaType: .audio,
                    preferredTrackID: kCMPersistentTrackID_Invalid
                )
            }
            try audioTrack?.insertTimeRange(insertRange, of: srcAudio, at: timelineStart)
        }
    }

    // Filter + transition pass. If any clip carries filters OR a non-zero
    // head/tail fade (from TransitionActionTool's synthetic Effect-track clip),
    // build an AVMutableVideoComposition with a CIFilter-chain handler keyed
    // on composition time → owning clip. Fades render as a dip-to-black:
    // after all filters run on the frame, a `CIColorMatrix` scales RGB by the
    // current alpha so the frame ramps to/from black at the clip boundaries.
    //
    // LUT filters need file I/O + a `.cube` parse before they can render;
    // do that here (outside the handler) and cache by asset id so the same
    // `.cube` isn't parsed once per clip that references it.
    var lutCache: [String: LutPayload] = [:]
    var filterRanges: [ClipFilterRange] = []
    for plan in plans {
        let hasFades = plan.headFadeSeconds > 0 || plan.tailFadeSeconds > 0
        if plan.filters.isEmpty && !hasFades { continue }
        var specs: [FilterSpec] = []
        for raw in plan.filters {
            var lut: LutPayload? = nil
            if raw.name.lowercased() == "lut", let aidRaw = raw.assetIdRaw {
                if let cached = lutCache[aidRaw] {
                    lut = cached
                } else {
                    let aid = IosBridgesKt.assetId(value: aidRaw)
                    let lutPath = try await resolver.resolve(assetId: aid)
                    let url = URL(fileURLWithPath: lutPath)
                    let text = try String(contentsOf: url, encoding: .utf8)
                    let bridged = IosBridgesKt.parseCubeLutForCoreImage(text: text)
                    let payload = LutPayload(
                        dimension: Int(bridged.size),
                        data: bridged.data as Data
                    )
                    lutCache[aidRaw] = payload
                    lut = payload
                }
            }
            specs.append(FilterSpec(
                name: raw.name,
                params: raw.params.reduce(into: [String: Double]()) { acc, kv in
                    acc[kv.key] = kv.value.doubleValue
                },
                lut: lut
            ))
        }
        filterRanges.append(ClipFilterRange(
            start: plan.timelineStartSeconds,
            end: plan.timelineStartSeconds + plan.timelineDurationSeconds,
            filters: specs,
            headFade: plan.headFadeSeconds,
            tailFade: plan.tailFadeSeconds
        ))
    }

    var videoComposition: AVMutableVideoComposition?
    if !filterRanges.isEmpty {
        videoComposition = try await AVMutableVideoComposition.videoComposition(
            with: composition,
            applyingCIFiltersWithHandler: { request in
                let source = request.sourceImage.clampedToExtent()
                let t = CMTimeGetSeconds(request.compositionTime)
                var out = source
                // First clip whose [start, end) brackets the current time
                // owns this frame. Clips don't overlap on a single video
                // track so the first match is unambiguous.
                if let range = filterRanges.first(where: { t >= $0.start && t < $0.end }) {
                    for spec in range.filters {
                        out = applyCoreFilter(spec, to: out)
                    }
                    let alpha = transitionAlphaAt(t, clip: range)
                    if alpha < 1.0 {
                        out = dimToBlack(out, alpha: alpha)
                    }
                }
                // The compositor expects the output image's extent to match
                // the request's render size. Cropping keeps CIGaussianBlur's
                // grown extent from bleeding into the final frame.
                request.finish(with: out.cropped(to: source.extent), context: nil)
            }
        )
    }

    // Subtitle pass. `Track.Subtitle` clips are burned in via
    // `AVVideoCompositionCoreAnimationTool` — a CATextLayer per subtitle is
    // anchored bottom-center and gated to its timeline window by an opacity
    // animation. When there are subtitles but no filter pass, we still need
    // an AVMutableVideoComposition so the animation tool has somewhere to
    // attach; `videoComposition(withPropertiesOf:)` builds one with the
    // default per-track instructions.
    let subtitles = timeline.toIosSubtitlePlan()
    if !subtitles.isEmpty {
        if videoComposition == nil {
            videoComposition = try await AVMutableVideoComposition.videoComposition(withPropertiesOf: composition)
        }
        guard let vc = videoComposition else {
            // Defensive — the two branches above guarantee vc is non-nil.
            _ = adapter.tryEmit(event: RenderProgressFailed(jobId: jobId, message: "failed to build video composition for subtitles"))
            adapter.close()
            return
        }
        let renderSize = vc.renderSize != .zero
            ? vc.renderSize
            : CGSize(width: Int(output.resolution.width), height: Int(output.resolution.height))
        let (parent, video) = buildSubtitleLayers(subtitles: subtitles, renderSize: renderSize)
        vc.animationTool = AVVideoCompositionCoreAnimationTool(
            postProcessingAsVideoLayer: video,
            in: parent
        )
    }

    // Prepare output file — AVAssetExportSession refuses to overwrite.
    let outURL = URL(fileURLWithPath: output.targetPath)
    try? FileManager.default.removeItem(at: outURL)
    try? FileManager.default.createDirectory(
        at: outURL.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )

    let preset = exportPreset(for: output.resolution.height)
    guard let session = AVAssetExportSession(asset: composition, presetName: preset) else {
        _ = adapter.tryEmit(event: RenderProgressFailed(jobId: jobId, message: "AVAssetExportSession init failed (preset=\(preset))"))
        adapter.close()
        return
    }
    session.outputURL = outURL
    session.outputFileType = .mp4
    session.shouldOptimizeForNetworkUse = true
    if let videoComposition {
        session.videoComposition = videoComposition
    }

    // Progress polling. AVAssetExportSession doesn't push; we poll `.progress`
    // while the session is exporting. 100ms matches the Android Transformer
    // polling cadence.
    let progressTask = Task.detached {
        while !Task.isCancelled {
            let status = session.status
            if status != .exporting && status != .waiting {
                break
            }
            _ = adapter.tryEmit(event: RenderProgressFrames(
                jobId: jobId,
                ratio: session.progress,
                message: nil
            ))
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
        session.exportAsynchronously {
            continuation.resume(returning: ())
        }
    }
    progressTask.cancel()

    switch session.status {
    case .completed:
        _ = adapter.tryEmit(event: RenderProgressCompleted(jobId: jobId, outputPath: output.targetPath))
    case .cancelled:
        _ = adapter.tryEmit(event: RenderProgressFailed(jobId: jobId, message: "export cancelled"))
    case .failed:
        let err = session.error?.localizedDescription ?? "unknown export failure"
        _ = adapter.tryEmit(event: RenderProgressFailed(jobId: jobId, message: err))
    default:
        _ = adapter.tryEmit(event: RenderProgressFailed(
            jobId: jobId,
            message: "unexpected export session status: \(session.status.rawValue)"
        ))
    }
    adapter.close()
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

    /// Identifier consumed by ExportTool's per-clip render-cache fingerprint
    /// — different engines must produce mutually-incompatible cache keys so
    /// switching engines invalidates mezzanines. AVFoundation does not yet
    /// implement the per-clip incremental render path (CLAUDE.md "Known
    /// incomplete" — only the FFmpeg engine has `supportsPerClipCache=true`),
    /// so this id only ever participates in the whole-timeline render path's
    /// own bookkeeping.
    var engineId: String { "avfoundation" }

    /// AVFoundation goes through the whole-timeline render path; the per-clip
    /// `renderClip` / `concatMezzanines` primitives are FFmpeg-only today.
    /// Returning `false` keeps `ExportTool` on the legacy
    /// `runWholeTimelineRender` branch, matching the current contract on this
    /// platform.
    var supportsPerClipCache: Bool { false }

    // swiftlint:disable identifier_name
    func __probe(
        source: MediaSource,
        completionHandler: @escaping @Sendable (MediaMetadata?, (any Error)?) -> Void
    ) {
        // `MediaSource` is a sealed Kotlin class; only `.File` is resolvable to
        // an AVURLAsset on iOS without platform-side download (HTTP) or
        // PHAsset resolution (Platform). `.BundleFile` requires a per-render
        // `MediaPathResolver` to translate the bundle-relative path; the
        // probe / thumbnail callsites don't carry one (they receive a raw
        // `MediaSource`), so reject loud — same shape as Http / Platform.
        let path: String
        switch onEnum(of: source) {
        case .file(let file):
            path = file.path
        case .bundleFile:
            completionHandler(nil, avfError("BundleFile MediaSource needs per-render MediaPathResolver — probe/thumbnail callsites don't carry one"))
            return
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
                    bitrate: nil,
                    comment: nil
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
        // `time` is kotlin.time.Duration nanoseconds (see IosBridges comments).
        // Convert → CMTime via seconds for AVAssetImageGenerator.
        let seconds = IosBridgesKt.durationToSeconds(d: time)

        let path: String
        switch onEnum(of: source) {
        case .file(let file):
            path = file.path
        case .bundleFile:
            completionHandler(nil, avfError("BundleFile MediaSource needs per-render MediaPathResolver — probe/thumbnail callsites don't carry one"))
            return
        case .http:
            completionHandler(nil, avfError("Http MediaSource not supported (download first)"))
            return
        case .platform(let platform):
            completionHandler(nil, avfError("Platform MediaSource (\(platform.scheme)) not resolvable in AVFoundationVideoEngine"))
            return
        }

        let url = URL(fileURLWithPath: path)
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        // Allow any frame, not just keyframes — callers often want a frame at
        // a precise timecode (e.g. thumbnail of a clipped segment's start).
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero

        let requestTime = CMTime(seconds: seconds, preferredTimescale: 600)

        // Use the synchronous `copyCGImage(at:actualTime:)` API wrapped on a
        // background thread. The async `image(at:)` variant is iOS 16+ but
        // isn't uniformly stable across Xcode toolchain versions; the
        // synchronous path is the well-trodden route and the work happens
        // off-main via `Task.detached`.
        Task.detached {
            do {
                var actualTime = CMTime.zero
                let cgImage = try generator.copyCGImage(at: requestTime, actualTime: &actualTime)
                // CGImage → PNG bytes. Using CGImageDestination avoids pulling
                // UIImage into the render path (which would block on
                // UIKit's main-thread assumptions for some operations).
                let data = NSMutableData()
                guard let destination = CGImageDestinationCreateWithData(
                    data, UTType.png.identifier as CFString, 1, nil
                ) else {
                    completionHandler(nil, avfError("CGImageDestination creation failed"))
                    return
                }
                CGImageDestinationAddImage(destination, cgImage, nil)
                guard CGImageDestinationFinalize(destination) else {
                    completionHandler(nil, avfError("CGImageDestination finalize failed"))
                    return
                }

                // Transfer NSData → Kotlin ByteArray.
                let bytes = data as Data
                let kotlinBytes = KotlinByteArray(size: Int32(bytes.count))
                for (i, byte) in bytes.enumerated() {
                    // Byte is unsigned in Swift's Data but Kotlin's Byte is
                    // signed. Reinterpret bit-pattern via Int8 cast.
                    kotlinBytes.set(index: Int32(i), value: Int8(bitPattern: byte))
                }
                completionHandler(kotlinBytes, nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }
    // swiftlint:enable identifier_name

    // swiftlint:disable identifier_name

    /// Per-clip mezzanine render path — FFmpeg-only today (CLAUDE.md
    /// "Known incomplete"). `supportsPerClipCache` returns `false` so
    /// `ExportTool` never reaches this stub on AVFoundation; it exists
    /// only to satisfy the protocol contract that Kotlin's default
    /// implementation can't fulfil through the SKIE-bridged ObjC
    /// surface.
    func __renderClip(
        clip: Clip.Video,
        fades: TransitionFades?,
        output: OutputSpec,
        mezzaninePath: String,
        resolver: (any MediaPathResolver)?,
        completionHandler: @escaping @Sendable ((any Error)?) -> Void
    ) {
        completionHandler(avfError("renderClip not supported by AVFoundationVideoEngine (supportsPerClipCache=false)"))
    }

    /// Stub for the per-clip cache liveness check — AVFoundation never
    /// writes mezzanines, so this is unreachable in production. Returns
    /// `true` so a hypothetical caller treats "no mezzanine work needed"
    /// as a present cache rather than triggering re-render — matches
    /// Kotlin's default-true semantics.
    func __mezzaninePresent(
        path: String,
        completionHandler: @escaping @Sendable (KotlinBoolean?, (any Error)?) -> Void
    ) {
        completionHandler(KotlinBoolean(value: true), nil)
    }

    /// Stub for mezzanine deletion — no mezzanines were ever written by
    /// AVFoundation, so we return `false` (no file went). Matches
    /// Kotlin's default-false semantics for engines without a per-clip
    /// path.
    func __deleteMezzanine(
        path: String,
        completionHandler: @escaping @Sendable (KotlinBoolean?, (any Error)?) -> Void
    ) {
        completionHandler(KotlinBoolean(value: false), nil)
    }

    /// Stub — see `renderClip` rationale.
    func __concatMezzanines(
        mezzaninePaths: [String],
        subtitles: [Clip.Text],
        output: OutputSpec,
        completionHandler: @escaping @Sendable ((any Error)?) -> Void
    ) {
        completionHandler(avfError("concatMezzanines not supported by AVFoundationVideoEngine (supportsPerClipCache=false)"))
    }
    // swiftlint:enable identifier_name

    func render(
        timeline: Timeline,
        output: OutputSpec,
        resolver: (any MediaPathResolver)?
    ) -> SkieSwiftFlow<any RenderProgress> {
        let jobId = UUID().uuidString
        let adapter = SwiftRenderFlowAdapter()
        _ = adapter.tryEmit(event: RenderProgressStarted(jobId: jobId))

        // Per-render resolver override falls back to the engine-scope resolver
        // wired at construction time. ExportTool passes a per-project
        // BundleMediaPathResolver here so AIGC + bundle assets resolve against
        // the loaded bundle's root.
        let effectiveResolver = resolver ?? self.resolver

        // The Kotlin-side Flow returned by `adapter.asFlow()` is cold and
        // doesn't hold work on its own — we kick off the export in a detached
        // Task and push events into the adapter from there.
        Task.detached {
            do {
                try await runExport(
                    timeline: timeline,
                    output: output,
                    jobId: jobId,
                    resolver: effectiveResolver,
                    adapter: adapter
                )
            } catch {
                _ = adapter.tryEmit(event: RenderProgressFailed(
                    jobId: jobId,
                    message: (error as NSError).localizedDescription
                ))
                adapter.close()
            }
        }

        return adapter.asFlow()
    }
}
