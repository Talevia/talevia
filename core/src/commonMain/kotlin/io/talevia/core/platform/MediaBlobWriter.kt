package io.talevia.core.platform

import io.talevia.core.domain.MediaSource

/**
 * Write an opaque byte blob to platform-appropriate durable storage and hand
 * back a [MediaSource] that `MediaStorage.import` can ingest. Kept separate
 * from [MediaStorage] because `InMemoryMediaStorage` lives in commonMain and
 * has no way to touch a filesystem; the JVM impl ([FileBlobWriter]) lives in
 * jvmMain where `java.io.File` is available.
 *
 * Typical consumer: AIGC tools that receive bytes (e.g. a PNG) from a
 * provider and need to persist them before registering them as a
 * [io.talevia.core.domain.MediaAsset].
 */
fun interface MediaBlobWriter {
    /**
     * @param bytes the raw blob to persist.
     * @param suggestedExtension lowercase extension without leading dot (e.g. "png").
     *   Implementations MAY use it to name the file but are not required to.
     * @return a [MediaSource] referring to the persisted bytes.
     */
    suspend fun writeBlob(bytes: ByteArray, suggestedExtension: String): MediaSource
}
