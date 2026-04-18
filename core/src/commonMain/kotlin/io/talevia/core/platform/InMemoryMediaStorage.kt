package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * In-memory [MediaStorage] for tests and the M2 desktop demo. Persistent storage is a
 * later concern (asset library proper goes into ProjectStore once we model it).
 */
@OptIn(ExperimentalUuidApi::class)
class InMemoryMediaStorage : MediaStorage {
    private val assets = mutableMapOf<AssetId, MediaAsset>()
    private val mutex = Mutex()

    override suspend fun import(
        source: MediaSource,
        explicitId: AssetId?,
        probe: suspend (MediaSource) -> MediaMetadata,
    ): MediaAsset {
        val metadata = probe(source)
        val asset = MediaAsset(
            id = explicitId ?: AssetId(Uuid.random().toString()),
            source = source,
            metadata = metadata,
        )
        mutex.withLock { assets[asset.id] = asset }
        return asset
    }

    override suspend fun get(id: AssetId): MediaAsset? = mutex.withLock { assets[id] }

    override suspend fun list(): List<MediaAsset> = mutex.withLock { assets.values.toList() }

    override suspend fun delete(id: AssetId) = mutex.withLock { assets.remove(id); Unit }
}
