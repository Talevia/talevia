package io.talevia.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaSourceBundleFileTest {

    @Test
    fun `accepts simple posix relative path`() {
        val src = MediaSource.BundleFile("media/abc.mp3")
        assertEquals("media/abc.mp3", src.relativePath)
    }

    @Test
    fun `accepts deeply nested relative path`() {
        val src = MediaSource.BundleFile("media/aigc/2026/song.wav")
        assertEquals("media/aigc/2026/song.wav", src.relativePath)
    }

    @Test
    fun `rejects blank path`() {
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("   ") }
    }

    @Test
    fun `rejects leading slash absolute path`() {
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("/etc/passwd") }
    }

    @Test
    fun `rejects backslash separators`() {
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("media\\abc.mp3") }
    }

    @Test
    fun `rejects double-dot segment`() {
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("media/../../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("media/sub/..") }
    }

    @Test
    fun `rejects windows drive letter prefix`() {
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("C:/Windows/system32") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("c:relative") }
    }

    @Test
    fun `tolerates leading dot for hidden files`() {
        // Single-dot segments aren't considered traversal; they just resolve in place.
        // We don't strip them; resolver handles it.
        val src = MediaSource.BundleFile("media/.gitkeep")
        assertEquals("media/.gitkeep", src.relativePath)
    }
}
