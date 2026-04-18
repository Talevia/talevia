import AVFoundation
import Foundation
import TaleviaCore

/// Native AVFoundation implementation of `VideoEngine` from the Kotlin core.
///
/// **Status (M3 / workaround pass):** scaffold only. SKIE's Swift bridging for
/// KMP `fun interface` + value classes + `kotlin.time.Duration` requires a
/// couple of manual adapter shims we haven't built yet (`MediaPathResolver`
/// surfaces as an ObjC protocol but Swift doesn't see it under a clean name;
/// `Duration` flows through as `Int64` nanoseconds; value classes like
/// `AssetId` are erased to `Any`). The scaffold is intentionally narrow so
/// the Xcode build succeeds — the full implementation lands when we iterate
/// in a real Mac + simulator loop (see `docs/IOS_INTEGRATION.md` §5).
///
/// The Kotlin core already has `FfmpegVideoEngine` on JVM and `Media3VideoEngine`
/// on Android; porting those concat-style semantics to AVMutableComposition is
/// straightforward once the bridge shim is in place.
enum AVFoundationVideoEngine {
    static func isReachable() -> Bool { true }
}
