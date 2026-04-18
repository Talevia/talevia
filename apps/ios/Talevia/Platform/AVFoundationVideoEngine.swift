import AVFoundation
import Foundation
import TaleviaCore

/// Native AVFoundation implementation of `VideoEngine` from the Kotlin core.
///
/// SKIE exposes Kotlin `suspend` functions as Swift `async`, sealed classes as Swift
/// enums, and `Flow<T>` as `AsyncSequence`-style adapters. Implementing the Kotlin
/// interface from Swift uses those bridges directly.
///
/// Scope (M3): mirror the M2 desktop demo — concat sequential video clips into one
/// output file with `AVMutableComposition` + `AVAssetExportSession`. Filters /
/// transitions / multi-track mixing land in later milestones.
final class AVFoundationVideoEngine: NSObject, VideoEngine {

    // MARK: probe

    func probe(source: MediaSource) async throws -> MediaMetadata {
        let url = try Self.url(for: source)
        let asset = AVURLAsset(url: url)
        let durationSeconds = try await asset.load(.duration).seconds
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let videoTrack = tracks.first
        let resolution: Resolution? = try await {
            guard let track = videoTrack else { return nil }
            let size = try await track.load(.naturalSize)
            return Resolution(width: Int32(size.width), height: Int32(size.height))
        }()
        let frameRate: FrameRate? = try await {
            guard let track = videoTrack else { return nil }
            let fps = try await track.load(.nominalFrameRate)
            return fps > 0 ? FrameRate(numerator: Int32(fps.rounded()), denominator: 1) : nil
        }()
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        let audioTrack = audioTracks.first

        return MediaMetadata(
            duration: DurationKt.seconds(KotlinDouble(double: durationSeconds)),
            resolution: resolution,
            frameRate: frameRate,
            videoCodec: nil,
            audioCodec: nil,
            sampleRate: nil,
            channels: nil,
            bitrate: nil
        )
    }

    // MARK: render

    func render(timeline: Timeline, output: OutputSpec) -> Flow {
        // SKIE bridges Kotlin Flow to a `SkieSwiftFlow` that we can construct from
        // an AsyncSequence. Implementation note: AVAssetExportSession reports
        // progress via KVO; we poll its `progress` property between exports.
        return SkieKotlinFlow { continuation in
            let task = Task {
                do {
                    let jobId = UUID().uuidString
                    try await continuation.send(RenderProgressStarted(jobId: jobId))

                    let composition = AVMutableComposition()
                    let videoTrack = composition.addMutableTrack(
                        withMediaType: .video,
                        preferredTrackID: kCMPersistentTrackID_Invalid
                    )!
                    let audioTrack = composition.addMutableTrack(
                        withMediaType: .audio,
                        preferredTrackID: kCMPersistentTrackID_Invalid
                    )

                    var cursor = CMTime.zero
                    for clip in Self.videoClips(in: timeline) {
                        let url = URL(fileURLWithPath: clip.assetId.value)
                        let asset = AVURLAsset(url: url)
                        let srcStart = CMTime(seconds: clip.sourceRange.start.toDouble(unit: DurationUnit.seconds), preferredTimescale: 600)
                        let srcDuration = CMTime(seconds: clip.sourceRange.duration.toDouble(unit: DurationUnit.seconds), preferredTimescale: 600)
                        let timeRange = CMTimeRange(start: srcStart, duration: srcDuration)

                        if let v = try await asset.loadTracks(withMediaType: .video).first {
                            try videoTrack.insertTimeRange(timeRange, of: v, at: cursor)
                        }
                        if let a = try await asset.loadTracks(withMediaType: .audio).first {
                            try audioTrack?.insertTimeRange(timeRange, of: a, at: cursor)
                        }
                        cursor = CMTimeAdd(cursor, srcDuration)
                    }

                    let outURL = URL(fileURLWithPath: output.targetPath)
                    try? FileManager.default.removeItem(at: outURL)

                    guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
                        try await continuation.send(RenderProgressFailed(jobId: jobId, message: "could not create exporter"))
                        return
                    }
                    exporter.outputURL = outURL
                    exporter.outputFileType = .mp4

                    let progressTask = Task {
                        while !Task.isCancelled, exporter.status == .exporting || exporter.status == .waiting {
                            try? await continuation.send(RenderProgressFrames(jobId: jobId, ratio: exporter.progress, message: nil))
                            try? await Task.sleep(nanoseconds: 250_000_000)
                        }
                    }

                    await exporter.export()
                    progressTask.cancel()

                    if exporter.status == .completed {
                        try await continuation.send(RenderProgressCompleted(jobId: jobId, outputPath: output.targetPath))
                    } else {
                        try await continuation.send(RenderProgressFailed(jobId: jobId, message: exporter.error?.localizedDescription ?? "unknown"))
                    }
                    continuation.finish()
                } catch {
                    continuation.fail(error)
                }
            }
            return AnyCancellable { task.cancel() }
        }
    }

    // MARK: thumbnail

    func thumbnail(asset: AssetId, source: MediaSource, time: KotlinxDatetimeKotlinTimeDuration) async throws -> KotlinByteArray {
        let url = try Self.url(for: source)
        let urlAsset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: urlAsset)
        generator.appliesPreferredTrackTransform = true
        let cmTime = CMTime(seconds: time.toDouble(unit: DurationUnit.seconds), preferredTimescale: 600)
        let cgImage = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<CGImage, Error>) in
            generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: cmTime)]) { _, image, _, _, error in
                if let error = error { cont.resume(throwing: error) }
                else if let image = image { cont.resume(returning: image) }
                else { cont.resume(throwing: NSError(domain: "talevia.thumbnail", code: -1)) }
            }
        }
        let uiImage = UIImage(cgImage: cgImage)
        guard let png = uiImage.pngData() else {
            throw NSError(domain: "talevia.thumbnail", code: -2, userInfo: [NSLocalizedDescriptionKey: "PNG encode failed"])
        }
        return KotlinByteArray.from(data: png)
    }

    // MARK: helpers

    private static func url(for source: MediaSource) throws -> URL {
        switch onEnum(of: source) {
        case .file(let f): return URL(fileURLWithPath: f.path)
        case .http(let h): return URL(string: h.url)!
        case .platform(let p):
            // PHAsset / MediaStore tokens go through the iOS-side MediaStorage; the
            // engine never gets one here in the M3 demo.
            throw NSError(domain: "talevia.video", code: -10,
                          userInfo: [NSLocalizedDescriptionKey: "Platform MediaSource (\(p.scheme)) not handled by AVFoundationVideoEngine"])
        }
    }

    private static func videoClips(in timeline: Timeline) -> [Clip.Video] {
        var out: [Clip.Video] = []
        for track in timeline.tracks {
            if case let .video(v) = onEnum(of: track) {
                for clip in v.clips {
                    if case let .video(vc) = onEnum(of: clip) { out.append(vc) }
                }
            }
        }
        return out.sorted { $0.timeRange.start.toDouble(unit: DurationUnit.seconds) < $1.timeRange.start.toDouble(unit: DurationUnit.seconds) }
    }
}

private extension KotlinByteArray {
    static func from(data: Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        for (i, b) in data.enumerated() { array.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return array
    }
}
