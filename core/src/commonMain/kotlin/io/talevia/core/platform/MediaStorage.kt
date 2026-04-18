package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource

/**
 * Per-platform handle to media assets. Different platforms hold media very differently
 * (iOS Photos library, Android MediaStore, raw filesystem on Desktop/Server) so the
 * Core only knows about [MediaSource] tokens and [MediaAsset] metadata.
 */
interface MediaStorage {
    /**
     * @param explicitId workaround for the M2 demo: a tool may pass the file path as
     *   the AssetId so that downstream ffmpeg invocations can dereference the asset
     *   without an extra resolver. Pass `null` to let the storage allocate a UUID.
     */
    suspend fun import(
        source: MediaSource,
        explicitId: AssetId? = null,
        probe: suspend (MediaSource) -> io.talevia.core.domain.MediaMetadata,
    ): MediaAsset
    suspend fun get(id: AssetId): MediaAsset?
    suspend fun list(): List<MediaAsset>
    suspend fun delete(id: AssetId)
}
