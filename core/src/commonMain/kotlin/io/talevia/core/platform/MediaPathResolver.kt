package io.talevia.core.platform

import io.talevia.core.AssetId

/**
 * Narrow contract the render pipeline uses to go from an [AssetId] to a local
 * filesystem path it can feed into ffmpeg / AVFoundation / Media3.
 *
 * Implementations live with [MediaStorage] (which is-a resolver). Keeping the
 * resolver separate lets the [VideoEngine] depend on just this function without
 * pulling in the full storage read/write surface.
 *
 * Replaces the M2 workaround where `AssetId.value` itself was required to be a
 * filesystem path.
 */
fun interface MediaPathResolver {
    /**
     * @throws IllegalStateException when the asset cannot be resolved to a local
     *   file (e.g. an HTTP asset that hasn't been downloaded yet).
     */
    suspend fun resolve(assetId: AssetId): String
}
