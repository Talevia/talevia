package io.talevia.android

import io.talevia.core.domain.MediaSource
import io.talevia.core.platform.MediaBlobWriter
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Android counterpart of `core/jvmMain/.../FileBlobWriter`. Persists generated
 * blobs under `<cacheDir>/talevia-generated/` so frame extracts and any future
 * AIGC outputs survive long enough to be re-imported as MediaAssets without
 * leaking into permanent app storage.
 *
 * Cache dir is the right tier: the OS may evict it under storage pressure, but
 * generated blobs are reproducible (re-run the tool) and Project state holds
 * the canonical reference, so eviction is recoverable.
 */
@OptIn(ExperimentalUuidApi::class)
class AndroidFileBlobWriter(private val rootDir: File) : MediaBlobWriter {

    override suspend fun writeBlob(bytes: ByteArray, suggestedExtension: String): MediaSource {
        if (!rootDir.exists()) rootDir.mkdirs()
        val ext = suggestedExtension.trimStart('.').ifBlank { "bin" }
        val file = File(rootDir, "${Uuid.random()}.$ext")
        file.writeBytes(bytes)
        return MediaSource.File(file.absolutePath)
    }
}
