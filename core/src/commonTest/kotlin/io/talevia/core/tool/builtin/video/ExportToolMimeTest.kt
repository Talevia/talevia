package io.talevia.core.tool.builtin.video

import io.talevia.core.tool.builtin.video.export.mimeTypeFor
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportToolMimeTest {
    @Test
    fun knownVideoExtensionsMapToConcreteMime() {
        assertEquals("video/mp4", mimeTypeFor("/tmp/out.mp4"))
        assertEquals("video/mp4", mimeTypeFor("x.M4V"))
        assertEquals("video/quicktime", mimeTypeFor("x.MOV"))
        assertEquals("video/webm", mimeTypeFor("a/b/c.webm"))
        assertEquals("video/x-matroska", mimeTypeFor("x.mkv"))
        assertEquals("video/x-msvideo", mimeTypeFor("X.AVI"))
    }

    @Test
    fun imageAndAudioExtensionsRecognised() {
        assertEquals("image/gif", mimeTypeFor("out.gif"))
        assertEquals("audio/mpeg", mimeTypeFor("out.mp3"))
        assertEquals("audio/mp4", mimeTypeFor("out.m4a"))
        assertEquals("audio/wav", mimeTypeFor("out.wav"))
    }

    @Test
    fun unknownExtensionFallsBackToOctetStream() {
        assertEquals("application/octet-stream", mimeTypeFor("out.xyz"))
        assertEquals("application/octet-stream", mimeTypeFor("noextension"))
    }
}
