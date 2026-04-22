package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.RecentsRegistry
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileBundleBlobWriterTest {

    private fun setup(): Triple<FileBundleBlobWriter, FileProjectStore, FakeFileSystem> {
        val fs = FakeFileSystem()
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        val store = FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
        )
        val writer = FileBundleBlobWriter(store, fs)
        return Triple(writer, store, fs)
    }

    @Test
    fun writesBlobIntoBundleMediaDir() = runTest {
        val (writer, store, fs) = setup()
        val project = store.createAt("/projects/foo".toPath(), "Foo")

        val source = writer.writeBlob(
            projectId = project.id,
            assetId = AssetId("aigc-music-1"),
            bytes = byteArrayOf(1, 2, 3, 4),
            format = "mp3",
        )

        assertEquals(MediaSource.BundleFile("media/aigc-music-1.mp3"), source)
        val target = "/projects/foo/media/aigc-music-1.mp3".toPath()
        assertTrue(fs.exists(target))
        val read = fs.read(target) { readByteArray() }
        assertEquals(listOf<Byte>(1, 2, 3, 4), read.toList())
    }

    @Test
    fun normalizesEmptyFormatToBin() = runTest {
        val (writer, store, fs) = setup()
        val project = store.createAt("/projects/foo".toPath(), "Foo")

        val source = writer.writeBlob(project.id, AssetId("blob"), byteArrayOf(0), format = "")
        assertEquals("media/blob.bin", source.relativePath)
        assertTrue(fs.exists("/projects/foo/media/blob.bin".toPath()))
    }

    @Test
    fun stripsLeadingDotInFormat() = runTest {
        val (writer, store, _) = setup()
        val project = store.createAt("/projects/foo".toPath(), "Foo")

        val source = writer.writeBlob(project.id, AssetId("a"), byteArrayOf(1), format = ".png")
        assertEquals("media/a.png", source.relativePath)
    }

    @Test
    fun throwsWhenProjectNotRegistered() = runTest {
        val (writer, _, _) = setup()
        val ex = assertFailsWith<IllegalStateException> {
            writer.writeBlob(ProjectId("never-registered"), AssetId("a"), byteArrayOf(1), "mp3")
        }
        assertTrue(ex.message?.contains("not registered") == true)
    }

    @Test
    fun overwritesExistingFileAtomically() = runTest {
        val (writer, store, fs) = setup()
        val project = store.createAt("/projects/foo".toPath(), "Foo")

        writer.writeBlob(project.id, AssetId("dup"), byteArrayOf(1, 2), "mp3")
        writer.writeBlob(project.id, AssetId("dup"), byteArrayOf(3, 4, 5), "mp3")

        val read = fs.read("/projects/foo/media/dup.mp3".toPath()) { readByteArray() }
        assertEquals(listOf<Byte>(3, 4, 5), read.toList())
    }
}
