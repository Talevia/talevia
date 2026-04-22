package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Filesystem-backed [MediaStorage] that persists the asset catalog to
 * `<rootDir>/index.json` so [AssetId] references in saved Projects keep
 * resolving across process restarts.
 *
 * The catalog only stores [MediaAsset] metadata and the original
 * [MediaSource] — we do NOT copy source files into `rootDir`. That keeps
 * the behaviour identical to [InMemoryMediaStorage] for [MediaSource.File]
 * (resolve returns the original on-disk path) and avoids duplicating
 * potentially large video files. Users who want a managed content store
 * can layer that on top later.
 *
 * Thread-safety: all mutations go through [mutex]; writes are atomic via
 * a tempfile + `move(REPLACE_EXISTING, ATOMIC_MOVE)` so a crash mid-write
 * cannot leave a half-serialised index.
 */
@OptIn(ExperimentalUuidApi::class)
class FileMediaStorage(private val rootDir: File) : MediaStorage {

    private val indexFile: File = File(rootDir, INDEX_FILE_NAME)
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
            is MediaSource.BundleFile ->
                error("BundleFile source not resolvable in FileMediaStorage; use BundleMediaPathResolver")
            is MediaSource.Http -> error("Http sources must be downloaded before resolve()")
            is MediaSource.Platform -> error("Platform source (${s.scheme}) not resolvable in FileMediaStorage")
        }
    }

    private fun loadIndex(): MutableMap<AssetId, MediaAsset> {
        if (!indexFile.exists()) return mutableMapOf()
        val text = indexFile.readText()
        if (text.isBlank()) return mutableMapOf()
        val entries = JsonConfig.default.decodeFromString(ListSerializer(MediaAsset.serializer()), text)
        return entries.associateByTo(mutableMapOf()) { it.id }
    }

    private fun persist() {
        rootDir.mkdirs()
        val json = JsonConfig.default.encodeToString(
            ListSerializer(MediaAsset.serializer()),
            assets.values.toList(),
        )
        // Write to a sibling temp file then atomically move, so a crash
        // mid-write cannot leave the index half-serialised.
        val tmp = File.createTempFile(INDEX_FILE_NAME, ".tmp", rootDir)
        tmp.writeText(json)
        Files.move(
            tmp.toPath(),
            indexFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        private const val INDEX_FILE_NAME = "index.json"
    }
}
