import Foundation
import TaleviaCore

/// iOS counterpart of the JVM/Android FileBlobWriter. Persists generated
/// bytes (e.g. extracted frames) under the app's caches directory, then hands
/// back a `MediaSource.File` referring to the absolute path so
/// `MediaStorage.import` can ingest it without copying.
///
/// ## Protocol conformance notes
/// `MediaBlobWriter` is a Kotlin `fun interface` whose `writeBlob` is `suspend`.
/// SKIE exposes this via the same dual-surface pattern as `VideoEngine`:
/// implementers fulfil the callback-style `__writeBlob(bytes:suggestedExtension:completionHandler:)`
/// requirement and callers get a Swift `async throws` form for free.
final class IosFileBlobWriter: NSObject, MediaBlobWriter {

    private let rootDir: URL

    init(rootDir: URL) {
        self.rootDir = rootDir
        super.init()
        try? FileManager.default.createDirectory(at: rootDir, withIntermediateDirectories: true)
    }

    /// Default location: `<caches>/talevia-generated`. Caches survive across
    /// launches but the OS may evict under storage pressure — fine, since
    /// Project state holds the canonical asset reference and the source tool
    /// can re-derive the bytes.
    static func defaultRoot() -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        return caches.appendingPathComponent("talevia-generated", isDirectory: true)
    }

    // swiftlint:disable identifier_name
    func __writeBlob(
        bytes: KotlinByteArray,
        suggestedExtension: String,
        completionHandler: @escaping @Sendable (MediaSource?, (any Error)?) -> Void
    ) {
        let count = Int(bytes.size)
        var raw = [UInt8](repeating: 0, count: count)
        for i in 0..<count {
            // KotlinByteArray stores Int8; reinterpret bit-pattern → UInt8.
            raw[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        let data = Data(raw)
        let trimmed = suggestedExtension.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let ext = trimmed.isEmpty ? "bin" : trimmed
        let url = rootDir.appendingPathComponent("\(UUID().uuidString).\(ext)")

        Task.detached {
            do {
                try data.write(to: url, options: .atomic)
                completionHandler(MediaSource.File(path: url.path), nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }
    // swiftlint:enable identifier_name
}
