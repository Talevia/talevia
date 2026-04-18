package io.talevia.android

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.talevia.core.AssetId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Android implementation of [VideoEngine] using Media3's `Transformer` for export.
 * Probe metadata via `MediaMetadataRetriever`. Concat is via
 * `EditedMediaItemSequence` of clipped `EditedMediaItem`s.
 */
class Media3VideoEngine(private val context: Context) : VideoEngine {

    override suspend fun probe(source: MediaSource): MediaMetadata = withContext(Dispatchers.IO) {
        val path = sourceToPath(source)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val frameRate = frameRateStr?.toFloatOrNull()?.let { FrameRate(numerator = it.toInt(), denominator = 1) }
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            MediaMetadata(
                duration = durationMs.milliseconds,
                resolution = if (width != null && height != null) Resolution(width, height) else null,
                frameRate = frameRate,
                bitrate = bitrate,
            )
        } finally {
            retriever.release()
        }
    }

    override fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress> = flow {
        val jobId = UUID.randomUUID().toString()
        emit(RenderProgress.Started(jobId))

        val videoClips = videoClips(timeline)
        if (videoClips.isEmpty()) {
            emit(RenderProgress.Failed(jobId, "no video clips to render"))
            return@flow
        }

        val items = videoClips.map { c ->
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse("file://${c.assetId.value}"))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((c.sourceRange.start.inWholeMilliseconds))
                        .setEndPositionMs((c.sourceRange.start + c.sourceRange.duration).inWholeMilliseconds)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(mediaItem).build()
        }
        val sequence = EditedMediaItemSequence(items)
        val composition = Composition.Builder(listOf(sequence)).build()

        val outFile = File(output.targetPath)
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()

        val result = suspendCancellableCoroutine<Result<ExportResult>> { cont ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    cont.resume(Result.success(exportResult))
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    cont.resume(Result.failure(exportException))
                }
            })
            transformer.start(composition, outFile.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }

        result.onSuccess {
            emit(RenderProgress.Completed(jobId, output.targetPath))
        }.onFailure { e ->
            emit(RenderProgress.Failed(jobId, e.message ?: "media3 transformer error"))
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
    }

    private fun videoClips(t: Timeline): List<Clip.Video> = t.tracks.filterIsInstance<Track.Video>()
        .flatMap { it.clips.filterIsInstance<Clip.Video>() }
        .sortedBy { it.timeRange.start }
}
