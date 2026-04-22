package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import okio.Path

/**
 * Per-project [MediaPathResolver] that knows where to find the project's
 * bundle on disk. Constructed by [io.talevia.core.tool.builtin.video.ExportTool]
 * (and any other call site that hands a resolver to a [VideoEngine]) for
 * the duration of one render.
 *
 * Resolution rules:
 *  - [MediaSource.File] → returned as-is (machine-local absolute path the
 *    user provided when importing source footage)
 *  - [MediaSource.BundleFile] → joined with [bundleRoot] and converted to a
 *    string the engine can pass to ffmpeg / Media3 / AVFoundation
 *  - [MediaSource.Http] / [MediaSource.Platform] → not resolvable in the
 *    file-bundle context; throws with a clear hint
 *
 * The resolver does *not* check that the resolved file actually exists.
 * The render engine surfaces "file not found" errors itself with full
 * ffmpeg / native-engine context.
 */
class BundleMediaPathResolver(
    private val project: Project,
    private val bundleRoot: Path,
) : MediaPathResolver {
    override suspend fun resolve(assetId: AssetId): String {
        val asset = project.assets.find { it.id == assetId }
            ?: error("asset ${assetId.value} not found in project ${project.id.value}")
        return when (val src = asset.source) {
            is MediaSource.File -> src.path
            is MediaSource.BundleFile -> bundleRoot.resolve(src.relativePath).toString()
            is MediaSource.Http ->
                error(
                    "asset ${assetId.value} is an Http source — must be downloaded into the " +
                        "bundle before it can be rendered (use import_media with copy_into_bundle=true)",
                )
            is MediaSource.Platform ->
                error(
                    "asset ${assetId.value} is a Platform source (${src.scheme}) — not resolvable " +
                        "in the file-bundle context; needs a per-platform resolver",
                )
        }
    }
}
