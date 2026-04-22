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
 * In-memory [MediaStorage]. Assets are assigned a UUID [AssetId]; [resolve] maps
 * [AssetId] back to the underlying local file path when the source is
 * [MediaSource.File]. Other source kinds (HTTP / Platform) still fail — by design,
 * those need a per-platform resolver that downloads / dereferences them first.
 */
@OptIn(ExperimentalUuidApi::class)
class InMemoryMediaStorage : MediaStorage {
    private val assets = mutableMapOf<AssetId, MediaAsset>()
    private val mutex = Mutex()

    override suspend fun import(
        source: MediaSource,
        probe: suspend (MediaSource) -> MediaMetadata,
    ): MediaAsset {
        val metadata = probe(source)
        val asset = MediaAsset(
            id = AssetId(Uuid.random().toString()),
            source = source,
            metadata = metadata,
        )
        mutex.withLock { assets[asset.id] = asset }
        return asset
    }

    override suspend fun get(id: AssetId): MediaAsset? = mutex.withLock { assets[id] }

    override suspend fun list(): List<MediaAsset> = mutex.withLock { assets.values.toList() }

    override suspend fun delete(id: AssetId) = mutex.withLock { assets.remove(id); Unit }

    override suspend fun resolve(assetId: AssetId): String {
        val asset = mutex.withLock { assets[assetId] } ?: error("Unknown assetId $assetId")
        return when (val s = asset.source) {
            is MediaSource.File -> s.path
            is MediaSource.BundleFile ->
                error("BundleFile source not resolvable in InMemoryMediaStorage; use BundleMediaPathResolver")
            is MediaSource.Http -> error("Http sources must be downloaded before resolve()")
            is MediaSource.Platform -> error("Platform source (${s.scheme}) not resolvable in InMemoryMediaStorage")
        }
    }
}
