package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Timeline
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * No-op [VideoEngine] for `ProjectMaintenanceActionTool` tests that only
 * exercise the lockfile paths (prune-lockfile, gc-lockfile). The consolidated
 * tool's constructor requires an engine because gc-render-cache uses it, but
 * the lockfile actions never touch the engine. A shared object keeps each
 * test file from restating this fixture.
 */
internal object NoopMaintenanceEngine : VideoEngine {
    override suspend fun probe(source: MediaSource): MediaMetadata =
        MediaMetadata(duration = kotlin.time.Duration.ZERO)

    override fun render(
        timeline: Timeline,
        output: OutputSpec,
        resolver: MediaPathResolver?,
    ): Flow<RenderProgress> = emptyFlow()

    override suspend fun thumbnail(
        asset: AssetId,
        source: MediaSource,
        time: kotlin.time.Duration,
    ): ByteArray = ByteArray(0)

    override suspend fun deleteMezzanine(path: String): Boolean = false
}
