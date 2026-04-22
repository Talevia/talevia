package io.talevia.core.platform

import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.ProxyAsset

/**
 * Best-effort generator for [ProxyAsset]s attached to a freshly-imported
 * [MediaAsset]. VISION §5.3 performance lane — a 4K import should not force
 * every UI consumer to decode the full asset just to render a thumbnail.
 *
 * **Called synchronously from the import path.** [ImportMediaTool] invokes
 * `generate(asset)` after the asset lands in [MediaStorage] and merges the
 * returned proxies into the project's `MediaAsset.proxies` list. An
 * implementation that needs to do heavy work (ffmpeg frame extraction,
 * waveform rendering) can take seconds; the tool's `RenderProgress` part
 * keeps the UI un-frozen, but import itself is still a foreground
 * operation. The async variant — fire-and-forget, proxies appear via
 * BusEvent — is a future extension; the synchronous shape keeps the
 * first-cycle implementation tractable.
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
 * Media3 proxy generators without touching [ImportMediaTool].
 */
interface ProxyGenerator {
    suspend fun generate(asset: MediaAsset): List<ProxyAsset>
}

/**
 * Default no-op generator: returns an empty list for every asset. Used
 * wherever the composition root hasn't wired a concrete generator, so
 * [ImportMediaTool] can unconditionally call `proxyGenerator.generate`
 * without a null branch on the call site.
 */
object NoopProxyGenerator : ProxyGenerator {
    override suspend fun generate(asset: MediaAsset): List<ProxyAsset> = emptyList()
}
