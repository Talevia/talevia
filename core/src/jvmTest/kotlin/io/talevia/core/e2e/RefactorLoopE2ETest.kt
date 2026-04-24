package io.talevia.core.e2e

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.ReplayLockfileTool
import io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool
import io.talevia.core.tool.builtin.source.UpdateSourceNodeBodyTool
import io.talevia.core.tool.builtin.video.ExportTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end regression for the VISION §6 refactor loop — the flagship
 * workflow every piece of this system exists to support. Wires a real
 * `ToolRegistry` over fake engines and drives:
 *
 *   1. seed: a project with an AIGC-bound clip + matching lockfile entry
 *   2. edit character_ref (the "rename Mei's hair" step)
 *   3. assert the clip goes stale
 *   4. assert ExportTool refuses the render (stale-guard)
 *   5. call `regenerate_stale_clips`
 *   6. assert the clip's assetId flipped + staleness cleared
 *   7. call `export` again — now succeeds
 *
 * Guards: if a future refactor breaks the stale-guard, the
 * regenerate→replace swap, or the baseInputs replay, this test fails
 * before any CI-visible integration does. Single test class so the full
 * loop stays legible in one file.
 */
class RefactorLoopE2ETest {

    private class CountingImageEngine(
        /**
         * `false` (default, used by [editCharacterThenRegenerateThenExport]) —
         * bytes depend on call count, so successive calls with identical inputs
         * produce distinct bytes. That's the original M1 flavour where the
         * regenerate-swap path needs distinguishable artefacts.
         *
         * `true` (M2 criterion 6 "seed 复现证明") — bytes are a deterministic
         * function of `(seed, modelId, prompt, width, height)`, so two calls
         * with identical inputs produce bit-identical bytes. This models a
         * seed-stable provider (e.g. a local Stable Diffusion checkpoint); the
         * [replayLockfileReproducesSameProvenance] test relies on this to
         * prove that replay actually reproduces the generation when the
         * provider is deterministic. Non-deterministic providers are covered
         * by [ReplayLockfileTool]'s own docs — this stub is the "would be
         * bit-identical given a honest provider" half of the contract.
         */
        private val deterministic: Boolean = false,
    ) : ImageGenEngine {
        override val providerId: String = "fake"
        var calls: Int = 0
            private set

        /**
         * Captures the fully-folded prompt each provider call receives. Used by the
         * M1 criterion 5 semantic regression assertion (see the prompt fold-through
         * block in `editCharacterThenRegenerateThenExport`): if `AigcPipeline.foldPrompt`
         * stops physically injecting consistency-binding fields into the request, the
         * first element here stays identical between the seed call and the regenerate
         * call and the assertion flips red.
         */
        val capturedPrompts: MutableList<String> = mutableListOf()

        /**
         * Captures the raw `pngBytes` returned from each call. In
         * [deterministic] mode two calls with identical inputs must produce
         * `ByteArray.contentEquals`-equal entries here; that's the M2 criterion 6
         * "bit-identical provenance + bytes" check.
         */
        val capturedBytes: MutableList<ByteArray> = mutableListOf()

        override suspend fun generate(request: ImageGenRequest): ImageGenResult {
            calls += 1
            capturedPrompts += request.prompt
            val bytes = if (deterministic) {
                // 16-byte hash of every input that can change the output, so
                // identical inputs → identical bytes and differing seeds →
                // different bytes. Uses java.security.MessageDigest (jvmTest
                // only; commonTest would need a KMP primitive).
                val canonical = "seed=${request.seed}|model=${request.modelId}|prompt=${request.prompt}|" +
                    "w=${request.width}|h=${request.height}"
                java.security.MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray())
                    .copyOf(16)
            } else {
                val marker = calls.toByte()
                ByteArray(8) { marker }
            }
            capturedBytes += bytes
            return ImageGenResult(
                images = listOf(GeneratedImage(pngBytes = bytes, width = request.width, height = request.height)),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = request.seed,
                    parameters = JsonObject(emptyMap()),
                    // Fix the timestamp in deterministic mode too — otherwise
                    // identical-inputs-differ-on-createdAtEpochMs would leak
                    // non-determinism into [GenerationProvenance] itself.
                    createdAtEpochMs = if (deterministic) 1_700_000_000_000L else 1_700_000_000_000L + calls,
                ),
            )
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : BundleBlobWriter {
        override suspend fun writeBlob(
            projectId: io.talevia.core.ProjectId,
            assetId: io.talevia.core.AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile {
            val file = File(rootDir, "${assetId.value}.$format")
            file.writeBytes(bytes)
            return MediaSource.BundleFile("media/${file.name}")
        }
    }

    private class FakeVideoEngine : VideoEngine {
        var renderCalls: Int = 0
            private set

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> = flow {
            renderCalls += 1
            emit(RenderProgress.Started("job"))
            // Write a small stub file so ExportTool's happy path sees a real file.
            File(output.targetPath).writeBytes(byteArrayOf(0, 1, 2))
            emit(RenderProgress.Completed("job", output.targetPath))
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun editCharacterThenRegenerateThenExport() = runTest {
        val tmpDir = createTempDirectory("e2e-refactor").toFile()
        val outputFile = File(tmpDir, "final.mp4")

        val store = ProjectStoreTestKit.create()
        val imageEngine = CountingImageEngine()
        val videoEngine = FakeVideoEngine()
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, writer, store))
        registry.register(UpdateSourceNodeBodyTool(store))
        registry.register(RegenerateStaleClipsTool(store, registry))
        registry.register(ExportTool(store, videoEngine))

        // --- Seed: project with one clip bound to "mei". We drive the seed
        // through `generate_image` directly so baseInputs + provenance +
        // source-content snapshot all land authentically (no hand-rolled
        // LockfileEntry).
        val pid = ProjectId("e2e")
        store.upsert(
            "e2e",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = 5.seconds),
            ),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val genTool = registry["generate_image"]!!
        val genResult = genTool.dispatch(
            buildJsonObject {
                put("prompt", "portrait of Mei")
                put("seed", 42L)
                put("projectId", pid.value)
                put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""["mei"]"""))
                put("width", 512)
                put("height", 512)
            },
            ctx(),
        )
        val firstAssetId = JsonConfig.default.parseToJsonElement(
            registry["generate_image"]!!.encodeOutput(genResult).toString(),
        ).let { (it as JsonObject)["assetId"]!!.toString().trim('"') }
        assertEquals(1, imageEngine.calls)

        // Seed-call prompt fold-through check: the stub received not just the base
        // "portrait of Mei" text but the physically-injected character fragment from
        // AigcPipeline.foldPrompt — both the name and the original visualDescription
        // must land in the provider request. This pins M1 criterion 1 ("物理注入") to a
        // real provider-boundary assertion, so a future silent regression in
        // foldConsistencyIntoPrompt shows up here rather than as mysterious prompt
        // drift in production.
        val seedPrompt = imageEngine.capturedPrompts.single()
        assertTrue(
            seedPrompt.contains("Mei") && seedPrompt.contains("teal hair"),
            "seed prompt must contain folded character name + visualDescription, got: $seedPrompt",
        )

        // Place the generated asset on a video track, binding it to "mei"
        // so the DAG lane picks it up.
        store.mutate(pid) { p ->
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c-1"),
                                    timeRange = TimeRange(0.seconds, 3.seconds),
                                    sourceRange = TimeRange(0.seconds, 3.seconds),
                                    assetId = AssetId(firstAssetId),
                                    sourceBinding = setOf(SourceNodeId("mei")),
                                ),
                            ),
                        ),
                    ),
                    duration = 3.seconds,
                ),
            )
        }

        // --- 1. fresh project exports cleanly (baseline)
        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("outputPath", outputFile.absolutePath)
            },
            ctx(),
        )
        assertEquals(1, videoEngine.renderCalls)

        // --- 2. edit the character — this is the §6 "rename Mei's hair" step.
        // update_source_node_body is full-replacement; re-supply every character_ref
        // field we want to keep (name) alongside the mutated visualDescription.
        registry["update_source_node_body"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("nodeId", "mei")
                put(
                    "body",
                    buildJsonObject {
                        put("name", "Mei")
                        put("visualDescription", "red hair")
                    },
                )
            },
            ctx(),
        )

        // --- 3. clip is now stale (source hash drifted from the lockfile snapshot)
        val staleNow = store.get(pid)!!.staleClipsFromLockfile()
        assertEquals(1, staleNow.size)
        assertEquals("c-1", staleNow.single().clipId.value)

        // --- 4. export refuses the stale render; no new engine calls
        val ex = assertFailsWith<IllegalStateException> {
            registry["export"]!!.dispatch(
                buildJsonObject {
                    put("projectId", pid.value)
                    put("outputPath", outputFile.absolutePath)
                },
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("stale"), "export must refuse stale: ${ex.message}")
        assertEquals(1, videoEngine.renderCalls, "stale-guard must block the engine")

        // --- 5. regenerate — one new AIGC call, clip's assetId flips
        registry["regenerate_stale_clips"]!!.dispatch(
            buildJsonObject { put("projectId", pid.value) },
            ctx(),
        )
        assertEquals(2, imageEngine.calls, "regenerate must call the image engine exactly once")

        // --- 5a. M1 criterion 5 semantic chain: edited character_ref field → regenerate
        // → prompt physically contains the NEW value + no longer contains the OLD one.
        // Without this assertion, "regenerate runs" and "asset id flips" could both be
        // true while the re-fed prompt was stale (e.g. if RegenerateStaleClipsTool
        // replayed cached baseInputs instead of re-resolving consistency against
        // today's source). The two assertions together pin the whole refactor-loop
        // invariant: edit a visible character_ref field → downstream prompt updates.
        val regenPrompt = imageEngine.capturedPrompts.last()
        assertTrue(
            regenPrompt.contains("red hair"),
            "regenerate prompt must contain new visualDescription, got: $regenPrompt",
        )
        assertTrue(
            !regenPrompt.contains("teal hair"),
            "regenerate prompt must NOT contain the stale visualDescription, got: $regenPrompt",
        )

        val project = store.get(pid)!!
        val clip = project.timeline.tracks.first().clips.filterIsInstance<Clip.Video>().single()
        assertTrue(clip.assetId.value != firstAssetId, "clip assetId must flip after regenerate")
        assertEquals(setOf(SourceNodeId("mei")), clip.sourceBinding, "binding must survive swap")
        assertEquals(0, project.staleClipsFromLockfile().size, "nothing should be stale post-regen")

        // --- 6. export now succeeds — stale-guard clears, render runs again
        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("outputPath", outputFile.absolutePath)
            },
            ctx(),
        )
        assertEquals(2, videoEngine.renderCalls, "post-regen export must run the engine")
        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }

    /**
     * M2 criterion 6 (MILESTONES.md) — "seed 复现证明, via lockfile cache-hit".
     *
     * Given the project lockfile hashes `(tool id, model, seed, width, height,
     * effective prompt, bindings, negatives, refs, lora)` into a single
     * `inputHash` and `AigcPipeline.findCached` short-circuits before the
     * provider call, two back-to-back `generate_image` dispatches with
     * identical seed + inputs must:
     *
     *  1. hit the engine exactly once (the second is a cache lookup);
     *  2. return the same `assetId` (not a new UUID — the cached entry's);
     *  3. keep the lockfile at one entry (no duplicate append);
     *  4. surface `cacheHit=true` on the second call so the caller / UI can
     *     distinguish "we billed the provider again" from "we short-circuited".
     *
     * This is the "同 assetId" half of the M2 grep anchor. The companion
     * [replayLockfileReproducesSameProvenance] test covers the
     * "bit-identical `GenerationProvenance`" half via `replay_lockfile`.
     */
    @Test fun sameSeedSameInputsProducesSameAssetId() = runTest {
        val tmpDir = createTempDirectory("e2e-seed-repro").toFile()
        val store = ProjectStoreTestKit.create()
        val imageEngine = CountingImageEngine()
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, writer, store))

        val pid = ProjectId("e2e-seed")
        store.upsert(
            "e2e-seed",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO),
            ),
        )

        val inputs = buildJsonObject {
            put("prompt", "a single red apple on a white background")
            put("seed", 42L)
            put("projectId", pid.value)
            // Explicit empty binding so consistency-fold is deterministic (no
            // auto-pickup of whatever consistency nodes a future test adds).
            put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""[]"""))
            put("width", 256)
            put("height", 256)
            put("model", "stub-image-1")
        }

        // JsonConfig.default sets encodeDefaults=false, so fields at their default
        // (e.g. `cacheHit = false`) are elided from the JSON; absent == false.
        fun JsonObject.boolAt(key: String): Boolean =
            this[key]?.toString()?.toBoolean() ?: false

        val first = registry["generate_image"]!!.dispatch(inputs, ctx())
        val firstOut = registry["generate_image"]!!.encodeOutput(first) as JsonObject
        val firstAssetId = firstOut["assetId"]!!.toString().trim('"')
        val firstCacheHit = firstOut.boolAt("cacheHit")
        val firstSeed = firstOut["seed"]!!.toString().toLong()
        assertEquals(1, imageEngine.calls, "first call must hit the engine")
        assertEquals(false, firstCacheHit, "first call must not be a cache hit")
        assertEquals(42L, firstSeed, "seed must round-trip to provenance")

        val second = registry["generate_image"]!!.dispatch(inputs, ctx())
        val secondOut = registry["generate_image"]!!.encodeOutput(second) as JsonObject
        val secondAssetId = secondOut["assetId"]!!.toString().trim('"')
        val secondCacheHit = secondOut.boolAt("cacheHit")
        val secondSeed = secondOut["seed"]!!.toString().toLong()

        assertEquals(1, imageEngine.calls, "second call must short-circuit on lockfile cache")
        assertEquals(firstAssetId, secondAssetId, "same seed + inputs must resolve to the same assetId")
        assertEquals(true, secondCacheHit, "second call must surface cacheHit=true")
        assertEquals(42L, secondSeed, "cache-hit path must preserve provenance.seed")

        val project = store.get(pid)!!
        assertEquals(
            1, project.lockfile.entries.size,
            "cache-hit must NOT append a duplicate lockfile entry",
        )
        assertEquals(
            AssetId(firstAssetId), project.lockfile.entries.single().assetId,
            "the single lockfile entry must reference the shared assetId",
        )
    }

    /**
     * 反直觉边界 (§3a #9) — the negative half of seed reproducibility.
     *
     * If seed changes, the input hash changes, the cache misses, a fresh
     * engine call lands, and a distinct `assetId` is minted. Without this
     * assertion, [sameSeedSameInputsProducesSameAssetId] could pass trivially
     * if `findCached` returned the same entry for every call (e.g. a bug that
     * ignored the seed field in the hash).
     */
    @Test fun differentSeedProducesDifferentAssetId() = runTest {
        val tmpDir = createTempDirectory("e2e-seed-diff").toFile()
        val store = ProjectStoreTestKit.create()
        val imageEngine = CountingImageEngine()
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, writer, store))

        val pid = ProjectId("e2e-seed-diff")
        store.upsert(
            "e2e-seed-diff",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO),
            ),
        )

        fun dispatchWithSeed(seed: Long): String {
            val result = kotlinx.coroutines.runBlocking {
                registry["generate_image"]!!.dispatch(
                    buildJsonObject {
                        put("prompt", "a single red apple on a white background")
                        put("seed", seed)
                        put("projectId", pid.value)
                        put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""[]"""))
                        put("width", 256)
                        put("height", 256)
                        put("model", "stub-image-1")
                    },
                    ctx(),
                )
            }
            val out = registry["generate_image"]!!.encodeOutput(result) as JsonObject
            return out["assetId"]!!.toString().trim('"')
        }

        val firstAssetId = dispatchWithSeed(42L)
        val secondAssetId = dispatchWithSeed(43L)

        assertEquals(2, imageEngine.calls, "different seeds must each hit the engine")
        assertTrue(
            firstAssetId != secondAssetId,
            "different seeds must mint distinct assetIds (got the same: $firstAssetId)",
        )
        assertEquals(
            2, store.get(pid)!!.lockfile.entries.size,
            "different seeds must land two lockfile entries",
        )
    }

    /**
     * M2 criterion 6 second anchor — `ReplayLockfileTool` participates in the
     * seed-reproducibility verification.
     *
     * The cache-hit path ([sameSeedSameInputsProducesSameAssetId]) proves
     * "identical inputs → identical assetId, zero re-bill". But the
     * milestone also wants the stronger contract: *given a honest
     * deterministic provider*, the replay path produces a bit-identical
     * `GenerationProvenance` + identical output bytes — i.e. the lockfile
     * entry alone is enough to reconstruct the generation.
     *
     * We exercise that by:
     *
     *  1. generating an image with `seed=42` against a [CountingImageEngine]
     *     configured in deterministic mode (bytes are a pure function of
     *     `(seed, modelId, prompt, w, h)`);
     *  2. looking up the resulting lockfile entry's `inputHash`;
     *  3. calling `replay_lockfile` with that hash — this bypasses the cache
     *     and issues a fresh provider call with the original `baseInputs`;
     *  4. asserting the replay's `newInputHash == originalInputHash`
     *     (`inputHashStable=true`), the new `GenerationProvenance.seed`
     *     equals the original's, and the two provider calls produced
     *     byte-identical payloads (`capturedBytes[0].contentEquals(
     *     capturedBytes[1])`).
     *
     * The assetIds themselves differ by design — `GenerateImageTool` mints a
     * fresh `Uuid.random()` per generation rather than content-addressing on
     * bytes, so the second call lands a new lockfile entry next to the
     * original (the pattern `ReplayLockfileTool` documents as "pin the
     * winner"). What's bit-identical is *the provenance + the bytes*, which
     * is what VISION §5.2 "相同 source + 相同 toolchain 重跑产物是否
     * bit-identical" actually asks about.
     */
    @Test fun replayLockfileReproducesSameProvenance() = runTest {
        val tmpDir = createTempDirectory("e2e-replay-repro").toFile()
        val store = ProjectStoreTestKit.create()
        val imageEngine = CountingImageEngine(deterministic = true)
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, writer, store))
        registry.register(ReplayLockfileTool(registry, store))

        val pid = ProjectId("e2e-replay")
        store.upsert(
            "e2e-replay",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO),
            ),
        )

        // 1. Seed generation.
        val seedResult = registry["generate_image"]!!.dispatch(
            buildJsonObject {
                put("prompt", "a single red apple on a white background")
                put("seed", 42L)
                put("projectId", pid.value)
                put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""[]"""))
                put("width", 256)
                put("height", 256)
                put("model", "stub-image-1")
            },
            ctx(),
        )
        val seedOut = registry["generate_image"]!!.encodeOutput(seedResult) as JsonObject
        val seedAssetId = seedOut["assetId"]!!.toString().trim('"')
        assertEquals(1, imageEngine.calls, "seed generation must call the engine exactly once")

        val originalEntry = store.get(pid)!!.lockfile.entries.single()
        val originalInputHash = originalEntry.inputHash
        assertEquals(
            AssetId(seedAssetId), originalEntry.assetId,
            "lockfile entry's assetId must match the generate_image output",
        )
        assertEquals(42L, originalEntry.provenance.seed, "lockfile captures the seed")

        // 2. Replay.
        val replayResult = registry["replay_lockfile"]!!.dispatch(
            buildJsonObject {
                put("inputHash", originalInputHash)
                put("projectId", pid.value)
            },
            ctx(),
        )
        val replayOut = registry["replay_lockfile"]!!.encodeOutput(replayResult) as JsonObject
        val inputHashStable = replayOut["inputHashStable"]!!.toString().toBoolean()
        val newInputHash = replayOut["newInputHash"]!!.toString().trim('"')
        val newAssetId = replayOut["newAssetId"]!!.toString().trim('"')

        // 3a. Replay actually re-called the provider (cache was bypassed).
        assertEquals(
            2, imageEngine.calls,
            "replay_lockfile must bypass AigcPipeline.findCached and re-issue a provider call",
        )

        // 3b. The two lockfile entries coexist; original intact, replay appended.
        val entries = store.get(pid)!!.lockfile.entries
        assertEquals(2, entries.size, "replay must append a second entry, leaving the original intact")
        assertEquals(originalEntry.assetId, entries.first().assetId, "original lockfile entry must be untouched")

        // 3c. Core reproducibility contract: same inputHash, same seed, same
        // modelId, same parameters — i.e. bit-identical GenerationProvenance
        // modulo the createdAtEpochMs timestamp (which we've also pinned in
        // the deterministic stub).
        assertEquals(originalInputHash, newInputHash, "replay's newInputHash must equal the original")
        assertTrue(inputHashStable, "inputHashStable must be true when nothing drifted")
        val newEntry = entries.last()
        assertEquals(
            originalEntry.provenance.seed, newEntry.provenance.seed,
            "replay must preserve seed in GenerationProvenance",
        )
        assertEquals(
            originalEntry.provenance.modelId, newEntry.provenance.modelId,
            "replay must preserve modelId in GenerationProvenance",
        )
        assertEquals(
            originalEntry.provenance.parameters, newEntry.provenance.parameters,
            "replay must preserve provider parameters in GenerationProvenance",
        )
        assertEquals(
            originalEntry.provenance.createdAtEpochMs, newEntry.provenance.createdAtEpochMs,
            "with a deterministic stub the replay produces a bit-identical provenance, " +
                "including createdAtEpochMs",
        )

        // 3d. The bytes the provider returned are bit-identical — the physical
        // evidence that seed + inputs really did reproduce the artefact.
        assertEquals(2, imageEngine.capturedBytes.size)
        assertTrue(
            imageEngine.capturedBytes[0].contentEquals(imageEngine.capturedBytes[1]),
            "deterministic provider must emit bit-identical bytes for identical inputs " +
                "(seed=42, same prompt, same model, same dims)",
        )

        // 3e. AssetId differs by design (Uuid.random() per generation); we
        // assert it explicitly so a future content-addressed refactor
        // surfaces here rather than silently tightening the invariant.
        assertTrue(
            seedAssetId != newAssetId,
            "GenerateImageTool mints fresh UUIDs per call — replay's assetId is expected to differ",
        )
    }
}
