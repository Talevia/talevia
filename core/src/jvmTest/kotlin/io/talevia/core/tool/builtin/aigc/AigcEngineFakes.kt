package io.talevia.core.tool.builtin.aigc

import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GeneratedMusic
import io.talevia.core.platform.GeneratedVideo
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.MusicGenRequest
import io.talevia.core.platform.MusicGenResult
import io.talevia.core.platform.SynthesizedAudio
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.UpscaleRequest
import io.talevia.core.platform.UpscaleResult
import io.talevia.core.platform.UpscaledImage
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VideoGenRequest
import io.talevia.core.platform.VideoGenResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Shared "one-shot deterministic" AIGC engine fakes
 * (`debt-aigc-test-fake-extract`). Each engine returns a fixed asset
 * payload on every call and captures `lastRequest` + `calls` so tests
 * can assert what was sent. Used by the simple happy-path test cases
 * across `GenerateImageToolTest` / `GenerateVideoToolTest` /
 * `GenerateMusicToolTest` / `SynthesizeSpeechToolTest` /
 * `UpscaleAssetToolTest`.
 *
 * Tests with engine-specific state — fallback chains, warmup events,
 * call-count-keyed bytes, intentional failures — keep their own
 * inline fakes; the shared shapes here cover only the deterministic
 * single-success path.
 *
 * Naming follows the bullet's suggestion (`OneShot` / `Deterministic` /
 * `Failing`); only the OneShot variants land in this initial extract
 * because they're what the simple call sites actually need. Failing /
 * warming variants stay inline next to their tests until a second
 * caller emerges.
 *
 * Provenance:
 * - `providerId` defaults are stable strings (`fake-image` etc.) so
 *   AIGC pipeline metrics counter cardinality stays predictable.
 * - `modelVersion` defaults to `null` (matches OpenAI image-gen
 *   shape); pass `fixedModelVersion = "v1"` to exercise the
 *   filled-version path.
 * - `createdAtEpochMs` is fixed at `1_700_000_000_000L` so test
 *   assertions on lockfile entries don't depend on wall-clock time.
 *
 * Counters (`calls` / `lastRequest`) are intentionally `var` with
 * private setters — readable by tests, immune to accidental external
 * mutation.
 */

/**
 * Returns a single deterministic image with [bytes] as the PNG body.
 * Captures the most recent `ImageGenRequest` and increments `calls`
 * on each invocation. Use for happy-path tests where a single AIGC
 * dispatch must round-trip provenance + asset bytes correctly.
 */
class OneShotImageGenEngine(
    private val bytes: ByteArray,
    override val providerId: String = "fake-image",
    private val fixedSeed: Long? = null,
    private val fixedModelVersion: String? = null,
) : ImageGenEngine {
    var lastRequest: ImageGenRequest? = null
        private set
    var calls: Int = 0
        private set

    override suspend fun generate(request: ImageGenRequest): ImageGenResult {
        calls += 1
        lastRequest = request
        val image = GeneratedImage(
            pngBytes = bytes,
            width = request.width,
            height = request.height,
        )
        val params = buildJsonObject {
            put("prompt", JsonPrimitive(request.prompt))
            put("seed", JsonPrimitive(request.seed))
        }
        return ImageGenResult(
            images = listOf(image),
            provenance = GenerationProvenance(
                providerId = providerId,
                modelId = request.modelId,
                modelVersion = fixedModelVersion,
                seed = fixedSeed ?: request.seed,
                parameters = params,
                createdAtEpochMs = FAKE_PROVENANCE_EPOCH_MS,
            ),
        )
    }
}

/**
 * Returns a single deterministic video with [bytes] as the MP4 body.
 * Mirrors [OneShotImageGenEngine] for the video-gen lane.
 */
class OneShotVideoGenEngine(
    private val bytes: ByteArray,
    override val providerId: String = "fake-video",
    private val fixedModelVersion: String? = null,
) : VideoGenEngine {
    var lastRequest: VideoGenRequest? = null
        private set
    var calls: Int = 0
        private set

    override suspend fun generate(request: VideoGenRequest): VideoGenResult {
        calls += 1
        lastRequest = request
        val video = GeneratedVideo(
            mp4Bytes = bytes,
            width = request.width,
            height = request.height,
            durationSeconds = request.durationSeconds,
        )
        val params = buildJsonObject {
            put("prompt", JsonPrimitive(request.prompt))
            put("seed", JsonPrimitive(request.seed))
            put("seconds", JsonPrimitive(request.durationSeconds))
        }
        return VideoGenResult(
            videos = listOf(video),
            provenance = GenerationProvenance(
                providerId = providerId,
                modelId = request.modelId,
                modelVersion = fixedModelVersion,
                seed = request.seed,
                parameters = params,
                createdAtEpochMs = FAKE_PROVENANCE_EPOCH_MS,
            ),
        )
    }
}

/** Returns a single deterministic music track with [bytes] as the audio body. */
class OneShotMusicGenEngine(
    private val bytes: ByteArray,
    override val providerId: String = "fake-music",
) : MusicGenEngine {
    var lastRequest: MusicGenRequest? = null
        private set
    var calls: Int = 0
        private set

    override suspend fun generate(request: MusicGenRequest): MusicGenResult {
        calls += 1
        lastRequest = request
        return MusicGenResult(
            music = GeneratedMusic(
                audioBytes = bytes,
                format = request.format,
                durationSeconds = request.durationSeconds,
            ),
            provenance = GenerationProvenance(
                providerId = providerId,
                modelId = request.modelId,
                modelVersion = null,
                seed = request.seed,
                parameters = buildJsonObject {
                    put("prompt", JsonPrimitive(request.prompt))
                    put("seed", JsonPrimitive(request.seed))
                    put("dur", JsonPrimitive(request.durationSeconds))
                },
                createdAtEpochMs = FAKE_PROVENANCE_EPOCH_MS,
            ),
        )
    }
}

/** Returns a single deterministic TTS render with [bytes] as the audio body. */
class OneShotTtsEngine(
    private val bytes: ByteArray,
    override val providerId: String = "fake-tts",
) : TtsEngine {
    var lastRequest: TtsRequest? = null
        private set
    var calls: Int = 0
        private set

    override suspend fun synthesize(request: TtsRequest): TtsResult {
        calls += 1
        lastRequest = request
        return TtsResult(
            audio = SynthesizedAudio(audioBytes = bytes, format = request.format),
            provenance = GenerationProvenance(
                providerId = providerId,
                modelId = request.modelId,
                modelVersion = null,
                seed = 0L,
                parameters = buildJsonObject {
                    put("model", JsonPrimitive(request.modelId))
                    put("voice", JsonPrimitive(request.voice))
                    put("input", JsonPrimitive(request.text))
                },
                createdAtEpochMs = FAKE_PROVENANCE_EPOCH_MS,
            ),
        )
    }
}

/**
 * Returns a single deterministic upscale result with [bytes] as the
 * image body. Output dimensions = `baseWidth * scale × baseHeight *
 * scale` from the request.
 */
class OneShotUpscaleEngine(
    private val bytes: ByteArray,
    override val providerId: String = "fake-upscale",
    private val baseWidth: Int = 256,
    private val baseHeight: Int = 256,
) : UpscaleEngine {
    var lastRequest: UpscaleRequest? = null
        private set
    var calls: Int = 0
        private set

    override suspend fun upscale(request: UpscaleRequest): UpscaleResult {
        calls += 1
        lastRequest = request
        return UpscaleResult(
            image = UpscaledImage(
                imageBytes = bytes,
                format = request.format,
                width = baseWidth * request.scale,
                height = baseHeight * request.scale,
            ),
            provenance = GenerationProvenance(
                providerId = providerId,
                modelId = request.modelId,
                modelVersion = null,
                seed = request.seed,
                parameters = JsonObject(emptyMap()),
                createdAtEpochMs = FAKE_PROVENANCE_EPOCH_MS,
            ),
        )
    }
}

/**
 * Pinned wall-clock value the OneShot fakes use for
 * [GenerationProvenance.createdAtEpochMs] so test assertions on
 * lockfile entries / provenance hashes don't drift with system
 * time. 2023-11-14T22:13:20Z; arbitrary but stable.
 */
private const val FAKE_PROVENANCE_EPOCH_MS: Long = 1_700_000_000_000L
