package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource

/**
 * Per-platform handle to media assets. Different platforms hold media very differently
 * (iOS Photos library, Android MediaStore, raw filesystem on Desktop/Server) so the
 * Core only knows about [MediaSource] tokens and [MediaAsset] metadata.
 */
interface MediaStorage : MediaPathResolver {
    suspend fun import(
        source: MediaSource,
        probe: suspend (MediaSource) -> io.talevia.core.domain.MediaMetadata,
    ): MediaAsset
    suspend fun get(id: AssetId): MediaAsset?
    suspend fun list(): List<MediaAsset>
    suspend fun delete(id: AssetId)
}
