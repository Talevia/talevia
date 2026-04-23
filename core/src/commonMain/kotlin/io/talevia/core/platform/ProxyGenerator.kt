package io.talevia.core.platform

import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.ProxyAsset

/**
 * Best-effort generator for [ProxyAsset]s attached to a freshly-imported
 * [MediaAsset]. VISION §5.3 performance lane — a 4K import should not force
 * every UI consumer to decode the full asset just to render a thumbnail.
 *
 * **Called synchronously from the import path.** [io.talevia.core.tool.builtin.video.ImportMediaTool]
 * invokes `generate(asset, sourcePath)` after the asset is built and merges the
 * returned proxies into the project's `MediaAsset.proxies` list. An
 * implementation that needs to do heavy work (ffmpeg frame extraction, waveform
 * rendering) can take seconds; the tool's `RenderProgress` part keeps the UI
 * un-frozen, but import itself is still a foreground operation.
 *
 * **Best-effort.** Implementations SHOULD NOT throw on provider-side
 * failures (ffmpeg missing, container format unreadable, disk full) —
 * return an empty list instead. The import itself succeeds even when
 * proxy generation yields nothing, which is the degradation mode that
 * matches the "we never had proxies" pre-change status quo.
 *
 * The default binding is [NoopProxyGenerator] — non-JVM platforms
 * (iOS / Android) currently use it, so `import_media` there just doesn't
 * stamp proxies yet. Parity follow-ups can swap in AVFoundation /
 * Media3 proxy generators without touching [io.talevia.core.tool.builtin.video.ImportMediaTool].
 */
interface ProxyGenerator {
    /**
     * @param asset the freshly-built (not yet persisted) [MediaAsset] — used
     *   for its metadata (codec / resolution / duration) and id (proxy output
     *   directory name).
     * @param sourcePath absolute filesystem path to the asset's bytes. The
     *   caller is responsible for resolving [MediaAsset.source] to this path
     *   (absolute for [io.talevia.core.domain.MediaSource.File], bundle-joined
     *   for [io.talevia.core.domain.MediaSource.BundleFile]).
     */
    suspend fun generate(asset: MediaAsset, sourcePath: String): List<ProxyAsset>
}

/**
 * Default no-op generator: returns an empty list for every asset. Used
 * wherever the composition root hasn't wired a concrete generator, so
 * [io.talevia.core.tool.builtin.video.ImportMediaTool] can unconditionally
 * call `proxyGenerator.generate` without a null branch on the call site.
 */
object NoopProxyGenerator : ProxyGenerator {
    override suspend fun generate(asset: MediaAsset, sourcePath: String): List<ProxyAsset> = emptyList()
}
