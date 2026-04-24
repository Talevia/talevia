package io.talevia.android

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.talevia.core.AssetId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Android implementation of [VideoEngine] using Media3's `Transformer`
 * for export. Probe metadata via `MediaMetadataRetriever`. Concat is via
 * `EditedMediaItemSequence` of clipped `EditedMediaItem`s.
 *
 * Per-clip effect dispatch is split across sibling files
 * (`debt-split-android-media3-video-engine`, 2026-04-23):
 *  - [mapFilterToEffect] / [VignetteOverlay] — `Media3FilterEffects.kt`
 *  - [transitionFadesFor] / [fadeOverlaysFor] / [FadeBlackOverlay] /
 *    [ClipFades] — `Media3TransitionEffects.kt`
 *  - [subtitleClips] / [subtitleOverlaysFor] + `SubtitleTextOverlay` +
 *    `buildSpannable` — `Media3SubtitleEffects.kt`
 *
 * This file keeps the engine's `VideoEngine` contract wiring: probe,
 * render (composition + transformer + progress polling), thumbnail,
 * and the per-clip sourceToPath resolution.
 */
class Media3VideoEngine(
    private val context: Context,
    private val pathResolver: MediaPathResolver,
) : VideoEngine {

    override val engineId: String = "media3-android"

    override suspend fun probe(source: MediaSource): MediaMetadata = withContext(Dispatchers.IO) {
        val path = sourceToPath(source)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val frameRate = frameRateStr?.toFloatOrNull()?.let {
                io.talevia.core.domain.FrameRate(numerator = it.toInt(), denominator = 1)
            }
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            MediaMetadata(
                duration = durationMs.milliseconds,
                resolution = if (width != null && height != null) io.talevia.core.domain.Resolution(width, height) else null,
                frameRate = frameRate,
                bitrate = bitrate,
            )
        } finally {
            retriever.release()
        }
    }

    override fun render(
        timeline: Timeline,
        output: OutputSpec,
        resolver: MediaPathResolver?,
    ): Flow<RenderProgress> = callbackFlow {
        val effectiveResolver = resolver ?: pathResolver
        val jobId = UUID.randomUUID().toString()
        trySend(RenderProgress.Started(jobId))

        val videoClips = videoClips(timeline)
        if (videoClips.isEmpty()) {
            trySend(RenderProgress.Failed(jobId, "no video clips to render"))
            close()
            return@callbackFlow
        }

        // Pre-resolve every asset-bound filter (today: only `lut`). Media3's
        // effect builders are synchronous so we cannot call the suspend
        // resolver inside `mapFilterToEffect`. Resolving here also lets us
        // parse each `.cube` once per render even if multiple clips share
        // the same LUT asset.
        val filterAssetPaths = mutableMapOf<String, String>()
        videoClips.forEach { c ->
            c.filters.forEach { f ->
                val aid = f.assetId
                if (aid != null) filterAssetPaths.getOrPut(aid.value) { effectiveResolver.resolve(aid) }
            }
        }
        val lutCache = mutableMapOf<String, SingleColorLut>()
        val subtitles = subtitleClips(timeline)
        val transitionFades = transitionFadesFor(timeline, videoClips)

        val items = videoClips.map { c ->
            val resolvedPath = effectiveResolver.resolve(c.assetId)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse("file://$resolvedPath"))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((c.sourceRange.start.inWholeMilliseconds))
                        .setEndPositionMs((c.sourceRange.start + c.sourceRange.duration).inWholeMilliseconds)
                        .build(),
                )
                .build()
            val builder = EditedMediaItem.Builder(mediaItem)
            val videoEffects: MutableList<Effect> = c.filters
                .mapNotNull { mapFilterToEffect(it, filterAssetPaths, lutCache) }
                .toMutableList()
            // Build the overlay list: transition fades first (composited under
            // subtitles so the caption stays legible even while the image dips
            // to black — matches the FFmpeg pipeline, where drawtext runs
            // after the per-clip `fade` filter), then the subtitle overlays.
            val overlayList = buildList {
                addAll(fadeOverlaysFor(c, transitionFades))
                addAll(subtitleOverlaysFor(c, subtitles))
            }
            if (overlayList.isNotEmpty()) videoEffects += OverlayEffect(overlayList)
            if (videoEffects.isNotEmpty()) {
                builder.setEffects(Effects(emptyList(), videoEffects))
            }
            builder.build()
        }
        val sequence = EditedMediaItemSequence(items)
        val composition = Composition.Builder(listOf(sequence)).build()

        val outFile = File(output.targetPath)
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    trySend(RenderProgress.Completed(jobId, output.targetPath))
                    close()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    trySend(RenderProgress.Failed(jobId, exportException.message ?: "media3 transformer error"))
                    close()
                }
            })
            .build()

        transformer.start(composition, outFile.absolutePath)

        // Media3 doesn't push per-frame progress callbacks — poll getProgress() at
        // ~10 Hz while the export is running. ProgressHolder is a small mutable holder
        // the Transformer fills in synchronously on the same thread.
        val pollScope = CoroutineScope(Dispatchers.Default)
        pollScope.launch {
            val holder = androidx.media3.transformer.ProgressHolder()
            while (isActive) {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    trySend(RenderProgress.Frames(jobId, holder.progress / 100f))
                } else if (state == Transformer.PROGRESS_STATE_NOT_STARTED) {
                    break
                }
                delay(100)
            }
        }

        awaitClose {
            pollScope.cancel()
            transformer.cancel()
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceToPath(source))
                val frame = retriever.getFrameAtTime(time.toLong(DurationUnit.MICROSECONDS))
                    ?: error("no frame at $time")
                val baos = java.io.ByteArrayOutputStream()
                frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            } finally {
                retriever.release()
            }
        }

    private fun sourceToPath(source: MediaSource): String = when (source) {
        is MediaSource.File -> source.path
        is MediaSource.Http -> error("Http MediaSource not supported (download first)")
        is MediaSource.Platform -> when (source.scheme) {
            "content" -> source.value
            else -> error("Unknown platform scheme: ${source.scheme}")
        }
        // TODO(file-bundle-migration): BundleFile resolution requires the
        // per-render BundleMediaPathResolver — sourceToPath() is called from
        // probe() / extractFrame() paths that don't have one yet. The render
        // path already routes through pathResolver so it's covered.
        is MediaSource.BundleFile -> error(
            "BundleFile sources require a per-render BundleMediaPathResolver; " +
                "Media3VideoEngine.sourceToPath() does not have one in this code path",
        )
    }

    private fun videoClips(t: Timeline): List<Clip.Video> = t.tracks.filterIsInstance<Track.Video>()
        .flatMap { it.clips.filterIsInstance<Clip.Video>() }
        .sortedBy { it.timeRange.start }
}
