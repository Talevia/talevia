package io.talevia.core.domain.source

import io.talevia.core.domain.Clip

/**
 * Output modality of a [Clip] — visual frames vs. audio waveform. Used by
 * [io.talevia.core.domain.staleClipsFromLockfile] (VISION §3.2 + §5.5) so a
 * source-field edit only stales clips that actually consume that field's
 * modality.
 *
 * Concretely: a `character_ref` carries both `visualDescription` (image-gen
 * input) and `voiceId` (TTS input). Without modality awareness, flipping
 * `voiceId` re-hashes the whole node and stales every video clip bound to
 * the character — even though the visuals didn't depend on the voice. With
 * this enum we hash the visual slice and the audio slice separately, then
 * pick the slice that matches the consuming clip.
 *
 * Two-state on purpose: every clip kind we render today is one or the other.
 * If a future clip kind genuinely needs both (e.g. a baked clip that is
 * itself a video+audio render), it can compare against both modalities and
 * be stale-if-either — no enum addition needed.
 */
enum class Modality { Visual, Audio }

/**
 * Which slice of a source node's content this clip actually consumes. Pure
 * derivation from the clip kind:
 *
 *  - [Clip.Video] → [Modality.Visual]: image / video frames produced from
 *    visual prompts, reference images, LoRAs.
 *  - [Clip.Audio] → [Modality.Audio]: TTS / music waveforms produced from
 *    voice ids, lyrics, reference voices.
 *  - [Clip.Text] → [Modality.Visual]: text overlays are rendered into the
 *    visual frame. Text clips don't carry an `assetId` and therefore have
 *    no lockfile entry, so this branch is unreachable from the staleness
 *    detector — kept here to make the property total over the sealed
 *    hierarchy.
 */
val Clip.modalityNeeds: Modality
    get() = when (this) {
        is Clip.Video -> Modality.Visual
        is Clip.Audio -> Modality.Audio
        is Clip.Text -> Modality.Visual
    }
