package io.talevia.android

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.platform.MediaStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [MediaStorage] backed by a JSON index file in the app's internal storage directory.
 * Mirrors the JVM [io.talevia.core.platform.FileMediaStorage] but uses Android's
 * `context.filesDir` as the root and avoids NIO atomic-move (replaced by write+rename).
 *
 * Source files are NOT copied — we store the original [MediaSource] path. This is
 * intentional: the catalog survives restarts without duplicating video files.
 */
@OptIn(ExperimentalUuidApi::class)
class AndroidPersistentMediaStorage(rootDir: File) : MediaStorage {

    private val indexFile = File(rootDir, INDEX_NAME).also { rootDir.mkdirs() }
    private val mutex = Mutex()
    private val assets: MutableMap<AssetId, MediaAsset> = loadIndex()

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
        mutex.withLock {
            assets[asset.id] = asset
            persist()
        }
        return asset
    }

    override suspend fun get(id: AssetId): MediaAsset? = mutex.withLock { assets[id] }

    override suspend fun list(): List<MediaAsset> = mutex.withLock { assets.values.toList() }

    override suspend fun delete(id: AssetId) = mutex.withLock {
        if (assets.remove(id) != null) persist()
    }

    override suspend fun resolve(assetId: AssetId): String {
        val asset = mutex.withLock { assets[assetId] } ?: error("Unknown assetId $assetId")
        return when (val s = asset.source) {
            is MediaSource.File -> s.path
            is MediaSource.Http -> error("Http sources must be downloaded before resolve()")
            is MediaSource.Platform -> s.value
        }
    }

    private fun loadIndex(): MutableMap<AssetId, MediaAsset> {
        if (!indexFile.exists()) return mutableMapOf()
        val text = runCatching { indexFile.readText() }.getOrElse { return mutableMapOf() }
        if (text.isBlank()) return mutableMapOf()
        return runCatching {
            JsonConfig.default.decodeFromString(ListSerializer(MediaAsset.serializer()), text)
                .associateByTo(mutableMapOf()) { it.id }
        }.getOrElse { mutableMapOf() }
    }

    private fun persist() {
        val json = JsonConfig.default.encodeToString(
            ListSerializer(MediaAsset.serializer()),
            assets.values.toList(),
        )
        val tmp = File(indexFile.parentFile, "$INDEX_NAME.tmp")
        tmp.writeText(json)
        tmp.renameTo(indexFile)
    }

    companion object {
        private const val INDEX_NAME = "media-index.json"
    }
}
