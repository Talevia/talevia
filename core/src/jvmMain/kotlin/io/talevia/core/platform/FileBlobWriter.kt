package io.talevia.core.platform

import io.talevia.core.domain.MediaSource
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * JVM [MediaBlobWriter] that persists bytes under `<rootDir>/generated/` and
 * returns a [MediaSource.File] referring to the written path.
 *
 * Matches the layout assumption of [FileMediaStorage]: both write under
 * `TALEVIA_MEDIA_DIR`, and `FileMediaStorage.resolve` maps the stored
 * `MediaSource.File.path` back to that same absolute path, so a generated
 * image can be imported without any extra copy.
 *
 * Filenames are random UUIDs — deterministic enough to avoid collisions but
 * not intended to carry semantics; provenance lives on the
 * [io.talevia.core.domain.MediaAsset] / Project side, not the filename.
 */
@OptIn(ExperimentalUuidApi::class)
class FileBlobWriter(private val rootDir: File) : MediaBlobWriter {

    override suspend fun writeBlob(bytes: ByteArray, suggestedExtension: String): MediaSource {
        val generatedDir = File(rootDir, "generated")
        if (!generatedDir.exists()) generatedDir.mkdirs()
        val ext = suggestedExtension.trimStart('.').ifBlank { "bin" }
        val file = File(generatedDir, "${Uuid.random()}.$ext")
        file.writeBytes(bytes)
        return MediaSource.File(file.absolutePath)
    }
}
