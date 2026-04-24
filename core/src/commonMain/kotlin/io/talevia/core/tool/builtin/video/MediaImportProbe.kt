package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.ProxyGenerator
import io.talevia.core.platform.VideoEngine
import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Per-path probe / copy / proxy-generate pipeline for [ImportMediaTool].
 * Extracted from the dispatcher file so the main tool class stays under
 * the 500-line R.5.4 threshold. Behaviour is byte-identical to the
 * previous `ImportMediaTool.probeOne` / `shouldAutoCopy` /
 * `deduplicateProxies` methods.
 *
 * Bundles the probe's injected deps (engine / proxy generator / fs / blob
 * writer / clock / auto-copy threshold) into one class so the caller
 * passes `(path, bundleRoot, copyChoice, projectId)` per invocation
 * without re-threading the wiring.
 *
 * Never throws — every exception in the probe path maps to
 * [ProbeResult.Failure] so the batch aggregation in the caller stays a
 * straight-line `filterIsInstance<Success>()` / `filterIsInstance<Failure>()`.
 */
internal class MediaImportProbe(
    private val engine: VideoEngine,
    private val proxyGenerator: ProxyGenerator,
    private val fs: FileSystem,
    private val bundleBlobWriter: BundleBlobWriter,
    private val clock: Clock,
    private val autoInBundleThresholdBytes: Long,
) {
    /**
     * Probe + proxy-generate a single path, returning the stamped asset or a
     * captured failure.
     *
     * Copy-into-bundle decision per [copyChoice]:
     * - `true` → copy when [bundleRoot] is non-null (explicit-true without a
     *   bundle path already errored out in the caller before reaching here).
     * - `false` → reference-by-path.
     * - `null` (auto) → copy when [bundleRoot] is non-null AND the file is ≤
     *   [autoInBundleThresholdBytes]; else reference.
     *
     * When copying, bytes land at `<bundleRoot>/media/<assetId>.<ext>` and the
     * asset's source is [MediaSource.BundleFile]. Otherwise [MediaSource.File].
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun probe(
        path: String,
        bundleRoot: Path?,
        copyChoice: Boolean?,
        projectId: ProjectId,
    ): ProbeResult = runCatching {
        val metadata = engine.probe(MediaSource.File(path))
        val newAssetId = AssetId(Uuid.random().toString())
        val sourcePath: String
        val shouldCopy = when (copyChoice) {
            true -> bundleRoot != null
            false -> false
            null -> bundleRoot != null && shouldAutoCopy(path)
        }
        val source: MediaSource = if (!shouldCopy) {
            sourcePath = path
            MediaSource.File(path)
        } else {
            requireNotNull(bundleRoot) { "bundleRoot must be non-null when shouldCopy is true" }
            val ext = path.substringAfterLast('.', missingDelimiterValue = "bin")
                .ifBlank { "bin" }
            // Stream through BundleBlobWriter so the tmpfile + atomicMove
            // logic lives in exactly one place (shared with AIGC byte-path
            // writes via the same primitive). Bytes flow Source → sink
            // without materialising in a ByteArray — bundle copies of
            // 500 MB footage don't peak RSS.
            val bundleSource = bundleBlobWriter.writeBlobStreaming(
                projectId = projectId,
                assetId = newAssetId,
                source = fs.source(path.toPath()),
                format = ext,
            )
            sourcePath = bundleRoot.resolve(bundleSource.relativePath).toString()
            bundleSource
        }
        val asset = MediaAsset(id = newAssetId, source = source, metadata = metadata)
        val proxies = runCatching { proxyGenerator.generate(asset, sourcePath) }.getOrDefault(emptyList())
        val stamped = asset.copy(
            proxies = (asset.proxies + proxies).deduplicateProxies(),
            updatedAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        ProbeResult.Success(path = path, asset = stamped)
    }.getOrElse { t ->
        ProbeResult.Failure(
            path = path,
            message = t.message ?: t::class.simpleName ?: "unknown",
        )
    }

    /**
     * Auto-mode size gate. True ⇒ file is small enough to copy into the bundle.
     * When metadata is unavailable (okio returned null or the stat failed), we
     * err on the side of "reference" — auto mode is meant to be the
     * conservative default, and we'd rather leave a 0-byte file untouched
     * than blow up a bundle with an opaque gigabyte.
     */
    private fun shouldAutoCopy(path: String): Boolean {
        val size = runCatching { fs.metadata(path.toPath()).size }.getOrNull() ?: return false
        return size <= autoInBundleThresholdBytes
    }
}

/**
 * Outcome of a single-path probe — separate `Success` / `Failure` variants so
 * the caller can aggregate a batch via `filterIsInstance` without rethrowing.
 */
internal sealed interface ProbeResult {
    val path: String
    data class Success(override val path: String, val asset: MediaAsset) : ProbeResult
    data class Failure(override val path: String, val message: String) : ProbeResult
}

/**
 * De-dupe proxies by `(purpose, source)` so a repeated import doesn't
 * accumulate stale thumbnails when the generator re-runs on an already-
 * imported asset. Preserves insertion order — newer entries overwrite
 * same-key older ones via the `associateBy { … }.values` idiom.
 */
internal fun List<io.talevia.core.domain.ProxyAsset>.deduplicateProxies(): List<io.talevia.core.domain.ProxyAsset> {
    if (isEmpty()) return this
    return associateBy { it.purpose to it.source }.values.toList()
}
