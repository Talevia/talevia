package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStore
import okio.FileSystem

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
 */
fun interface BundleBlobWriter {
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
}

/**
 * Default [BundleBlobWriter] backed by Okio. Looks up the bundle root via
 * [ProjectStore.pathOf]; writes are atomic (tempfile + atomicMove) so a
 * partial write never leaves a half-written `media/<assetId>.<ext>`.
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
        val bundleRoot = projects.pathOf(projectId)
            ?: error("project ${projectId.value} is not registered — open or create the bundle first")
        val ext = format.trimStart('.').ifBlank { "bin" }
        val mediaDir = bundleRoot.resolve("media")
        fs.createDirectories(mediaDir)
        val target = mediaDir.resolve("${assetId.value}.$ext")
        val tmp = mediaDir.resolve("${target.name}.tmp.${randomSuffix()}")
        fs.write(tmp) { write(bytes) }
        fs.atomicMove(tmp, target)
        return MediaSource.BundleFile("media/${target.name}")
    }

    private fun randomSuffix(): String =
        kotlin.random.Random.nextLong().toString(radix = 36).removePrefix("-")
}
