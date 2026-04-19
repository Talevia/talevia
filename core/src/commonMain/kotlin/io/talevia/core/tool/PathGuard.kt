package io.talevia.core.tool

/**
 * Validation helpers for filesystem paths passed through tool inputs. The LLM
 * is not trusted — a prompt-injection attack could smuggle `../` or a device
 * path to read/write outside the intended workspace. We reject anything that
 * looks suspicious at the tool boundary rather than at the platform layer so
 * the error surfaces in a clear tool-dispatch failure.
 *
 * Policy is intentionally conservative:
 *  - path must be non-empty
 *  - path length bounded (DoS guard)
 *  - must not contain a `..` path segment
 *  - must not contain a NUL byte (some OSes treat `file\u0000.jpg` as `file`)
 *  - must not contain control characters that break shell-out (ffmpeg)
 *
 * We deliberately do NOT enforce "must be absolute" — tools may want to resolve
 * workspace-relative paths themselves. The per-tool helper calls can layer on
 * that requirement via [requireAbsolute].
 */
object PathGuard {
    const val MAX_PATH_LENGTH = 4096

    class InvalidPathException(path: String, reason: String) :
        IllegalArgumentException("Invalid path \"${path.take(80)}\": $reason")

    fun validate(path: String, requireAbsolute: Boolean = false) {
        if (path.isEmpty()) throw InvalidPathException(path, "empty")
        if (path.length > MAX_PATH_LENGTH) {
            throw InvalidPathException(path, "length ${path.length} exceeds $MAX_PATH_LENGTH")
        }
        if (path.any { it == '\u0000' }) throw InvalidPathException(path, "contains NUL byte")
        if (path.any { it.code in 1..31 && it != '\t' }) {
            throw InvalidPathException(path, "contains control character")
        }
        // Normalised segment check: split on both slashes so Windows-style separators
        // can't sneak `..\\` past a POSIX-focused filter.
        val segments = path.split('/', '\\')
        if (segments.any { it == ".." }) {
            throw InvalidPathException(path, "parent-directory traversal (..) not allowed")
        }
        if (requireAbsolute) {
            val isPosixAbs = path.startsWith('/')
            val isWindowsAbs = path.length >= 3 && path[1] == ':' && (path[2] == '\\' || path[2] == '/')
            if (!isPosixAbs && !isWindowsAbs) {
                throw InvalidPathException(path, "must be an absolute path")
            }
        }
    }
}
