import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation
import UIKit
import XCTest
@testable import Talevia
import TaleviaCore

/// Smoke test for the AVFoundation-backed `VideoEngine`.
///
/// The test synthesizes two 1-second solid-colour MP4s at runtime via
/// `AVAssetWriter` (portable across Xcode versions + avoids checking in
/// binary fixtures), imports them into the Kotlin core's `InMemoryMediaStorage`
/// through the real `ImportMediaTool` boundary, builds a `Timeline` with both
/// clips back-to-back, and runs the full render pipeline. The assertion is
/// pragmatic: a `Completed` event must arrive and the output file must exist
/// and have non-zero size.
///
/// This is a smoke test — not a frame-accuracy test. The narrower claim is
/// "the SKIE bridge + the AVMutableComposition + the export session + the
/// SwiftRenderFlowAdapter all plug together end-to-end".
@MainActor
final class AVFoundationVideoEngineSmokeTest: XCTestCase {

    func testRenderTwoClipsEndToEnd() async throws {
        let tmp = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("talevia-smoke-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let clipA = tmp.appendingPathComponent("a.mp4")
        let clipB = tmp.appendingPathComponent("b.mp4")
        try await writeSolidColourClip(url: clipA, seconds: 1.0, colour: .red)
        try await writeSolidColourClip(url: clipB, seconds: 1.0, colour: .green)

        let media = InMemoryMediaStorage()
        let engine = AVFoundationVideoEngine(resolver: media)

        // Probe + store each clip so MediaStorage knows the IDs the
        // composition will ask the resolver about. The iosMain helper
        // `importWithKnownMetadata` avoids the pain of constructing a
        // `KotlinSuspendFunction1` from Swift for the probe callback.
        let sourceA = MediaSource.File(path: clipA.path)
        let sourceB = MediaSource.File(path: clipB.path)
        let metaA = try await engine.probe(source: sourceA)
        let metaB = try await engine.probe(source: sourceB)
        let assetA = try await IosBridgesKt.importWithKnownMetadata(
            storage: media, source: sourceA, metadata: metaA
        )
        let assetB = try await IosBridgesKt.importWithKnownMetadata(
            storage: media, source: sourceB, metadata: metaB
        )

        let oneSecond = IosBridgesKt.durationOfSeconds(seconds: 1.0)
        let zero = IosBridgesKt.durationOfSeconds(seconds: 0.0)

        let clip0 = Clip.Video(
            id: IosBridgesKt.clipId(value: "c0"),
            timeRange: TimeRange(start: zero, duration: oneSecond),
            sourceRange: TimeRange(start: zero, duration: oneSecond),
            transforms: [],
            assetId: assetA.id,
            filters: [],
            sourceBinding: []
        )
        let clip1 = Clip.Video(
            id: IosBridgesKt.clipId(value: "c1"),
            timeRange: TimeRange(start: oneSecond, duration: oneSecond),
            sourceRange: TimeRange(start: zero, duration: oneSecond),
            transforms: [],
            assetId: assetB.id,
            filters: [],
            sourceBinding: []
        )
        let videoTrack = Track.Video(id: IosBridgesKt.trackId(value: "t0"), clips: [clip0, clip1])
        let timeline = Timeline(
            tracks: [videoTrack],
            duration: IosBridgesKt.durationOfSeconds(seconds: 2.0),
            frameRate: FrameRate(numerator: 30, denominator: 1),
            resolution: Resolution(width: 640, height: 480)
        )

        let outPath = tmp.appendingPathComponent("out.mp4").path
        let output = OutputSpec(
            targetPath: outPath,
            resolution: Resolution(width: 640, height: 480),
            frameRate: 30,
            videoBitrate: 4_000_000,
            audioBitrate: 128_000,
            videoCodec: "h264",
            audioCodec: "aac",
            container: "mp4"
        )

        var sawStarted = false
        var sawCompleted = false
        var lastError: String?
        let flow = engine.render(timeline: timeline, output: output)
        for try await event in flow {
            switch onEnum(of: event) {
            case .started:
                sawStarted = true
            case .frames:
                break
            case .completed:
                sawCompleted = true
            case .failed(let failed):
                lastError = failed.message
            }
        }

        XCTAssertTrue(sawStarted, "render should emit a Started event")
        XCTAssertTrue(sawCompleted, "render should emit a Completed event — last error: \(lastError ?? "nil")")
        let attrs = try FileManager.default.attributesOfItem(atPath: outPath)
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        XCTAssertGreaterThan(size, 0, "output file should exist with non-zero size")
    }

    // MARK: - Fixture synthesis

    /// Write a tiny solid-colour MP4 at `url`. Uses AVAssetWriter with the
    /// H.264 pixel-buffer adaptor — the file ends up ~20–50 KB and plays back
    /// via AVFoundation like any other MP4.
    private func writeSolidColourClip(url: URL, seconds: Double, colour: UIColor) async throws {
        try? FileManager.default.removeItem(at: url)
        let width = 320, height = 240
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ]
        )
        writer.add(input)
        guard writer.startWriting() else {
            throw NSError(domain: "fixture", code: -1, userInfo: [NSLocalizedDescriptionKey: "startWriting failed: \(writer.error?.localizedDescription ?? "?")"])
        }
        writer.startSession(atSourceTime: .zero)

        let fps: Int32 = 30
        let totalFrames = Int(seconds * Double(fps))
        let pixelBuffer = try makeSolidColourPixelBuffer(width: width, height: height, colour: colour)

        for i in 0..<totalFrames {
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 1_000_000) }
            let pts = CMTime(value: CMTimeValue(i), timescale: fps)
            if !adaptor.append(pixelBuffer, withPresentationTime: pts) {
                throw NSError(domain: "fixture", code: -1, userInfo: [NSLocalizedDescriptionKey: "adaptor.append failed: \(writer.error?.localizedDescription ?? "?")"])
            }
        }
        input.markAsFinished()
        await writer.finishWriting()
        if writer.status != .completed {
            throw NSError(domain: "fixture", code: -1, userInfo: [NSLocalizedDescriptionKey: "finishWriting status=\(writer.status.rawValue) err=\(writer.error?.localizedDescription ?? "?")"])
        }
    }

    private func makeSolidColourPixelBuffer(width: Int, height: Int, colour: UIColor) throws -> CVPixelBuffer {
        var buf: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferCGImageCompatibilityKey: true,
             kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary,
            &buf
        )
        guard status == kCVReturnSuccess, let buffer = buf else {
            throw NSError(domain: "fixture", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "CVPixelBufferCreate failed"])
        }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else {
            throw NSError(domain: "fixture", code: -1, userInfo: [NSLocalizedDescriptionKey: "CVPixelBufferGetBaseAddress failed"])
        }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        let colourspace = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: base, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: bytesPerRow, space: colourspace,
            bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue |
                        CGBitmapInfo.byteOrder32Little.rawValue
        ) else {
            throw NSError(domain: "fixture", code: -1, userInfo: [NSLocalizedDescriptionKey: "CGContext creation failed"])
        }
        ctx.setFillColor(colour.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return buffer
    }
}

// Small UIColor shim so the test doesn't need to import UIKit directly on
// the XCTest bundle config (already available through Talevia app deps).
private extension UIColor {
    static var red: UIColor { UIColor(red: 1, green: 0, blue: 0, alpha: 1) }
    static var green: UIColor { UIColor(red: 0, green: 1, blue: 0, alpha: 1) }
}
