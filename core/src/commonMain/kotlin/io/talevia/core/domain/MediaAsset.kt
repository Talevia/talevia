package io.talevia.core.domain

import io.talevia.core.AssetId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class MediaAsset(
    val id: AssetId,
    val source: MediaSource,
    val metadata: MediaMetadata,
    val proxies: List<ProxyAsset> = emptyList(),
    /**
     * Epoch-millis of the last structural change to this asset, or `null`
     * when the backing project blob predates recency tracking. Stamped by
     * [io.talevia.core.domain.FileProjectStore] on `upsert` against
     * the prior blob. Powers `project_query(select=assets, sortBy="recent")`;
     * nulls sort last so projects imported from older blobs tail
     * deterministically until their next mutation restamps them.
     */
    val updatedAtEpochMs: Long? = null,
)

@Serializable
sealed class MediaSource {
    /**
     * Absolute filesystem path. Machine-local — does **not** travel across collaborators.
     * Use for the user's source footage that stays outside the project bundle.
     */
    @Serializable @SerialName("file")
    data class File(val path: String) : MediaSource()

    /**
     * Path relative to the project bundle root, POSIX-style (forward slashes).
     * Travels with the project — used for AIGC products + small imported assets
     * (LUTs, fonts) that should reproduce on another machine.
     *
     * Resolved via [io.talevia.core.platform.BundleMediaPathResolver] which prepends the
     * loaded project's bundle directory. Path-traversal characters (`..`, leading `/`,
     * leading drive letters) are rejected at resolve time.
     */
    @Serializable @SerialName("bundle_file")
    data class BundleFile(val relativePath: String) : MediaSource() {
        init {
            require(relativePath.isNotBlank()) { "BundleFile relativePath must not be blank" }
            require(!relativePath.startsWith("/")) {
                "BundleFile relativePath must not start with '/' (got '$relativePath')"
            }
            require(!relativePath.contains("\\")) {
                "BundleFile relativePath must use POSIX forward slashes (got '$relativePath')"
            }
            require(!relativePath.split("/").any { it == ".." }) {
                "BundleFile relativePath must not contain '..' segments (got '$relativePath')"
            }
            require(!relativePath.matches(Regex("^[A-Za-z]:.*"))) {
                "BundleFile relativePath must not be a Windows-style absolute path (got '$relativePath')"
            }
        }
    }

    @Serializable @SerialName("http")
    data class Http(val url: String) : MediaSource()

    /** Platform-opaque reference (e.g. iOS PHAsset localIdentifier, Android MediaStore Uri). */
    @Serializable @SerialName("platform")
    data class Platform(val scheme: String, val value: String) : MediaSource()
}

@Serializable
data class MediaMetadata(
    val duration: Duration,
    val resolution: Resolution? = null,
    val frameRate: FrameRate? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitrate: Long? = null,
    /**
     * Container-level `comment` metadata tag when the source file carries
     * one — Talevia exports bake an
     * [io.talevia.core.domain.render.ProvenanceManifest] here so a probed
     * artifact can be traced back to its source Project + Timeline hash.
     * Null when the container has no comment tag (typical for
     * non-Talevia media).
     */
    val comment: String? = null,
)

@Serializable
data class ProxyAsset(
    val source: MediaSource,
    val purpose: ProxyPurpose,
    val resolution: Resolution? = null,
)

@Serializable
enum class ProxyPurpose {
    @SerialName("thumbnail") THUMBNAIL,
    @SerialName("low-res") LOW_RES,
    @SerialName("audio-waveform") AUDIO_WAVEFORM,
}
