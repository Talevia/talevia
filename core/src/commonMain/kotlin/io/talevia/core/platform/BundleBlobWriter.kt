package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStore
import okio.Buffer
import okio.FileSystem
import okio.Source
import okio.buffer
import okio.use

/**
 * Writes opaque byte blobs into a project bundle's `media/` directory and
 * returns a [MediaSource.BundleFile] referring to the persisted bytes (relative
 * to the bundle root). Used by AIGC tools so the produced asset travels with
 * the project — `git push` ships generated music / upscaled images to other
 * collaborators without needing to re-call the provider.
 *
 * Replaces the global `<TALEVIA_MEDIA_DIR>/generated/<uuid>.<ext>` model in
 * the old `FileBlobWriter`. The bundle layout is the source of truth; there
 * is no machine-wide media pool any more.
 *
 * Two entry points exist:
 *  - [writeBlob] takes a [ByteArray] — the fit for AIGC tools that receive
 *    the whole payload as bytes from a provider (generate_image / music /
 *    video etc.).
 *  - [writeBlobStreaming] takes an okio [Source] — the fit for callers that
 *    already hold an open file handle and want to avoid loading it into
 *    memory (import_media copy_into_bundle, consolidate_media_into_bundle).
 *    Default implementation consumes the source into bytes and delegates to
 *    [writeBlob]; production impls override this to stream directly to disk.
 */
interface BundleBlobWriter {
    /**
     * Persist [bytes] under `<bundleRoot>/media/<assetId>.<format>` (where
     * `<bundleRoot>` is the path the [projectId] is registered at) and return
     * a [MediaSource.BundleFile] pointing at the relative path inside the
     * bundle (`media/<assetId>.<format>`).
     *
     * @throws IllegalStateException when [projectId] is not registered with the
     *   underlying [ProjectStore] (the bundle has not been opened or created).
     */
    suspend fun writeBlob(
        projectId: ProjectId,
        assetId: AssetId,
        bytes: ByteArray,
        format: String,
    ): MediaSource.BundleFile

    /**
     * Stream [source] into `<bundleRoot>/media/<assetId>.<format>`. Callers
     * that already hold an open file handle (import / consolidate paths)
     * use this form so large files don't have to be buffered in memory.
     *
     * [source] is consumed (read to completion) and closed by the callee.
     *
     * Default implementation materialises [source] into bytes and delegates
     * to [writeBlob] — correct for small payloads and test fakes, but
     * defeats the streaming contract. Production implementations
     * (`FileBundleBlobWriter`) override this to stream directly to a
     * tmpfile + atomicMove so the bytes never all live in memory at once.
     */
    suspend fun writeBlobStreaming(
        projectId: ProjectId,
        assetId: AssetId,
        source: Source,
        format: String,
    ): MediaSource.BundleFile {
        val bytes = source.use { src ->
            Buffer().apply { writeAll(src) }.readByteArray()
        }
        return writeBlob(projectId, assetId, bytes, format)
    }
}

/**
 * Default [BundleBlobWriter] backed by Okio. Looks up the bundle root via
 * [ProjectStore.pathOf]; writes are atomic (tempfile + atomicMove) so a
 * partial write never leaves a half-written `media/<assetId>.<ext>`.
 *
 * Overrides [writeBlobStreaming] so streaming callers genuinely stream —
 * the bytes flow from [Source] → [okio.BufferedSink] → tmpfile → atomicMove
 * without ever materialising in a `ByteArray`. [writeBlob] composes on top
 * of that by wrapping the caller's [ByteArray] in an in-memory [Buffer]
 * (unavoidable — the bytes already exist) and delegating to
 * [writeBlobStreaming], keeping the tmpfile + atomicMove logic in one
 * place.
 */
class FileBundleBlobWriter(
    private val projects: ProjectStore,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : BundleBlobWriter {

    override suspend fun writeBlob(
        projectId: ProjectId,
        assetId: AssetId,
        bytes: ByteArray,
        format: String,
    ): MediaSource.BundleFile {
        val source = Buffer().apply { write(bytes) }
        return writeBlobStreaming(projectId, assetId, source, format)
    }

    override suspend fun writeBlobStreaming(
        projectId: ProjectId,
        assetId: AssetId,
        source: Source,
        format: String,
    ): MediaSource.BundleFile {
        val bundleRoot = projects.pathOf(projectId)
            ?: error("project ${projectId.value} is not registered — open or create the bundle first")
        val ext = format.trimStart('.').ifBlank { "bin" }
        val mediaDir = bundleRoot.resolve("media")
        fs.createDirectories(mediaDir)
        val target = mediaDir.resolve("${assetId.value}.$ext")
        val tmp = mediaDir.resolve("${target.name}.tmp.${randomSuffix()}")
        source.use { src ->
            fs.sink(tmp).buffer().use { sink -> sink.writeAll(src) }
        }
        fs.atomicMove(tmp, target)
        return MediaSource.BundleFile("media/${target.name}")
    }

    private fun randomSuffix(): String =
        kotlin.random.Random.nextLong().toString(radix = 36).removePrefix("-")
}
