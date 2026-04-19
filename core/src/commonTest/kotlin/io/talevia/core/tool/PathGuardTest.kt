package io.talevia.core.tool

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PathGuardTest {

    @Test fun acceptsOrdinaryAbsolutePosixPath() {
        PathGuard.validate("/tmp/out.mp4", requireAbsolute = true)
    }

    @Test fun acceptsWindowsAbsolutePath() {
        PathGuard.validate("C:\\users\\out.mp4", requireAbsolute = true)
        PathGuard.validate("C:/users/out.mp4", requireAbsolute = true)
    }

    @Test fun acceptsRelativeWhenAbsoluteNotRequired() {
        PathGuard.validate("clips/out.mp4")
        PathGuard.validate("out.mp4")
    }

    @Test fun rejectsEmptyPath() {
        assertFailsWith<PathGuard.InvalidPathException> { PathGuard.validate("") }
    }

    @Test fun rejectsOvershootLength() {
        assertFailsWith<PathGuard.InvalidPathException> {
            PathGuard.validate("/tmp/" + "a".repeat(PathGuard.MAX_PATH_LENGTH))
        }
    }

    @Test fun rejectsParentTraversalPosix() {
        assertFailsWith<PathGuard.InvalidPathException> {
            PathGuard.validate("/tmp/../etc/passwd", requireAbsolute = true)
        }
    }

    @Test fun rejectsParentTraversalWindows() {
        // Windows-style back-slash separators should ALSO fail even when we're
        // running on POSIX — the path came from the LLM, not the OS.
        assertFailsWith<PathGuard.InvalidPathException> {
            PathGuard.validate("C:\\work\\..\\etc\\hosts", requireAbsolute = true)
        }
    }

    @Test fun rejectsNulBytes() {
        assertFailsWith<PathGuard.InvalidPathException> {
            PathGuard.validate("/tmp/evil\u0000.jpg", requireAbsolute = true)
        }
    }

    @Test fun rejectsControlChars() {
        assertFailsWith<PathGuard.InvalidPathException> {
            PathGuard.validate("/tmp/a\nb.mp4", requireAbsolute = true)
        }
    }

    @Test fun rejectsRelativeWhenAbsoluteRequired() {
        assertFailsWith<PathGuard.InvalidPathException> {
            PathGuard.validate("out.mp4", requireAbsolute = true)
        }
    }
}
