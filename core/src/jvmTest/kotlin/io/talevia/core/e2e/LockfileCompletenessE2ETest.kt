package io.talevia.core.e2e

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.toolShimForImage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * M2 criterion 1 ("Lockfile 完整性") — round-trip e2e for the full field set
 * MILESTONES.md mandates on every successful AIGC generation:
 *
 *   (modelId, modelVersion?, seed, parameters, inputHash,
 *    sourceBindingContentHashes)
 *
 * The fields live on [io.talevia.core.domain.lockfile.LockfileEntry] either
 * directly ([LockfileEntry.inputHash], [LockfileEntry.sourceContentHashes])
 * or nested inside [LockfileEntry.provenance] ([GenerationProvenance.modelId],
 * [GenerationProvenance.modelVersion], [GenerationProvenance.seed],
 * [GenerationProvenance.parameters]) — all six are already in place as of this
 * test's birth. The purpose of this test is to *pin* that mapping so a future
 * refactor that strips one of the fields (or that silently stops populating
 * `sourceContentHashes` for bound generations) fails here instead of in a
 * cold-readable place months later.
 *
 * Anchored by the grep the milestone text calls for:
 *   - `class LockfileEntry` in `core/domain/lockfile/`
 *   - e2e test with `project.lockfile.entries` assertion
 *
 * Companion to [RefactorLoopE2ETest], which exercises overlapping but
 * different invariants (refactor loop, replay, seed-diff entry minting).
 * This test is deliberately narrow — single concern: every criterion field
 * is populated on a real AIGC round-trip + the cache-hit path does not
 * double-count.
 */
class LockfileCompletenessE2ETest {

    /**
     * Image engine stub that emits a fully-populated [GenerationProvenance]
     * including a non-empty `parameters` map and a non-null `modelVersion`
     * checkpoint — exactly the shape a real provider hands back. The M2
     * criterion asks for *presence*, not just "the field exists on the data
     * class", so the stub must exercise the non-default branch.
     */
    private class FullFieldsImageEngine : ImageGenEngine {
        override val providerId: String = "fake-complete"
        var calls: Int = 0
            private set

        override suspend fun generate(request: ImageGenRequest): ImageGenResult {
            calls += 1
            val bytes = ByteArray(4) { calls.toByte() }
            return ImageGenResult(
                images = listOf(GeneratedImage(pngBytes = bytes, width = request.width, height = request.height)),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    // Non-null — criterion asks for `modelVersion?`, but "?" just means
                    // nullable, not absent. Real providers (OpenAI image endpoints,
                    // Replicate slugs with `:<sha>` pins) do emit a version; we
                    // simulate that here so the test confirms the field round-trips
                    // when the provider actually supplies it.
                    modelVersion = "v-2026.04.23",
                    seed = request.seed,
                    // Non-empty — exercises the "provider-specific params map"
                    // branch rather than the `JsonObject(emptyMap())` default.
                    parameters = buildJsonObject {
                        put("prompt", request.prompt)
                        put("width", request.width)
                        put("height", request.height)
                        put("sampler", "euler")
                        put("steps", 30)
                    },
                    createdAtEpochMs = 1_700_000_000_000L + calls,
                ),
            )
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : BundleBlobWriter {
        override suspend fun writeBlob(
            projectId: ProjectId,
            assetId: AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile {
            val file = File(rootDir, "${assetId.value}.$format")
            file.writeBytes(bytes)
            return MediaSource.BundleFile("media/${file.name}")
        }
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("lockfile-completeness-session"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    /**
     * Happy-path round-trip: one AIGC call → one lockfile entry → every
     * criterion field non-null / non-empty and matching the request.
     *
     * This is the flagship assertion MILESTONES.md asks for. If any future
     * refactor drops one of the six fields (or stops writing
     * `sourceContentHashes` when `consistencyBindingIds` is non-empty, which
     * is the realistic regression surface — empty bindings today already
     * legitimately produce an empty map), this test fails loudly.
     */
    @Test fun generateImageProducesFullyPopulatedLockfileEntry() = runTest {
        val tmpDir = createTempDirectory("lockfile-completeness").toFile()
        val store = ProjectStoreTestKit.create()
        val imageEngine = FullFieldsImageEngine()
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(toolShimForImage(GenerateImageTool(imageEngine, writer, store)))

        val pid = ProjectId("lockfile-completeness")
        store.upsert(
            "lockfile-completeness",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO),
            ),
        )
        // Seed a consistency binding so `sourceBindingContentHashes` is
        // exercised with a non-empty map. Without a binding the field would
        // legitimately be empty, which would not distinguish "field works" from
        // "field silently no-ops".
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }

        val request = buildJsonObject {
            put("prompt", "portrait of Mei")
            put("seed", 42L)
            put("projectId", pid.value)
            put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""["mei"]"""))
            put("width", 512)
            put("height", 512)
            put("model", "stub-image-1")
        }
        registry["generate_image"]!!.dispatch(request, ctx())
        assertEquals(1, imageEngine.calls, "engine must be hit exactly once")

        val entries = store.get(pid)!!.lockfile.entries
        assertEquals(1, entries.size, "exactly one lockfile entry must land per successful generation")
        val entry = entries.single()

        // --- Criterion field 1: modelId
        assertEquals(
            "stub-image-1", entry.provenance.modelId,
            "modelId must be populated on GenerationProvenance",
        )

        // --- Criterion field 2: modelVersion? (nullable — presence not forced,
        // but when the provider supplies it, the value must round-trip)
        assertEquals(
            "v-2026.04.23", entry.provenance.modelVersion,
            "modelVersion must round-trip when provider supplies it",
        )

        // --- Criterion field 3: seed
        assertEquals(42L, entry.provenance.seed, "seed must round-trip from request to lockfile")

        // --- Criterion field 4: parameters
        assertTrue(
            entry.provenance.parameters.isNotEmpty(),
            "parameters map must be populated when provider supplies it, got empty object",
        )
        assertEquals(
            "euler", entry.provenance.parameters["sampler"]?.toString()?.trim('"'),
            "parameters payload must round-trip through the lockfile",
        )

        // --- Criterion field 5: inputHash
        assertTrue(
            entry.inputHash.isNotBlank(),
            "inputHash must be populated (non-blank hex digest)",
        )

        // --- Criterion field 6: sourceBindingContentHashes
        // This is what the criterion text spells `sourceBindingContentHashes`;
        // in our model it's `LockfileEntry.sourceContentHashes` — a per-binding
        // snapshot of `SourceNode.deepContentHashOf(...)` taken at write time.
        assertEquals(
            setOf(SourceNodeId("mei")), entry.sourceBinding,
            "sourceBinding must reflect the consistencyBindingIds the tool was dispatched with",
        )
        assertEquals(
            setOf(SourceNodeId("mei")), entry.sourceContentHashes.keys,
            "sourceContentHashes must have exactly one entry per bound source node",
        )
        val meiHash = entry.sourceContentHashes[SourceNodeId("mei")]
        assertNotNull(meiHash, "bound source node must have a content-hash snapshot")
        assertTrue(
            meiHash.isNotBlank(),
            "sourceContentHashes entry must be a non-blank hash, got '$meiHash'",
        )

        // Sanity: the assetId on the entry must match some asset that lives in
        // `project.assets` — the lockfile isn't a free-floating record, it
        // points at a real persisted artefact.
        val project = store.get(pid)!!
        assertTrue(
            project.assets.any { it.id == entry.assetId },
            "lockfile entry's assetId must resolve to a Project.assets row",
        )
    }

    /**
     * §3a #9 反直觉 corner — "what if the AIGC tool is called but cache-hits on
     * an existing entry?". The lockfile is an append-only ledger; double-
     * counting would corrupt `gc_lockfile` age/count verdicts and inflate
     * `session_query(select=spend)` aggregations even though no new provider
     * bill was incurred.
     *
     * Drive two back-to-back dispatches with identical inputs. The second must
     * short-circuit via `AigcPipeline.findCached` and must NOT append a
     * second lockfile entry — even though the tool *did* return a successful
     * [io.talevia.core.tool.builtin.aigc.GenerateImageOutput].
     */
    @Test fun cacheHitDoesNotDoubleCountLockfileEntries() = runTest {
        val tmpDir = createTempDirectory("lockfile-completeness-cache").toFile()
        val store = ProjectStoreTestKit.create()
        val imageEngine = FullFieldsImageEngine()
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(toolShimForImage(GenerateImageTool(imageEngine, writer, store)))

        val pid = ProjectId("lockfile-completeness-cache")
        store.upsert(
            "lockfile-completeness-cache",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO),
            ),
        )

        val request = buildJsonObject {
            put("prompt", "a single red apple on a white background")
            put("seed", 7L)
            put("projectId", pid.value)
            put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""[]"""))
            put("width", 256)
            put("height", 256)
            put("model", "stub-image-1")
        }

        registry["generate_image"]!!.dispatch(request, ctx())
        assertEquals(1, imageEngine.calls, "first dispatch must hit the engine")
        assertEquals(1, store.get(pid)!!.lockfile.entries.size, "first dispatch must append one entry")

        registry["generate_image"]!!.dispatch(request, ctx())
        assertEquals(1, imageEngine.calls, "second dispatch must cache-hit (no new engine call)")
        assertEquals(
            1, store.get(pid)!!.lockfile.entries.size,
            "cache-hit must NOT double-count — lockfile stays at one entry",
        )

        // Field completeness still holds after the cache hit: the entry we
        // look up must match the one we seeded, with every criterion field
        // intact (nothing got scrubbed on the read path).
        val entry = store.get(pid)!!.lockfile.entries.single()
        assertEquals(7L, entry.provenance.seed)
        assertEquals("stub-image-1", entry.provenance.modelId)
        assertEquals("v-2026.04.23", entry.provenance.modelVersion)
        assertTrue(entry.provenance.parameters.isNotEmpty())
        assertTrue(entry.inputHash.isNotBlank())
    }

    /**
     * Field-completeness serialization round-trip — the lockfile is a
     * git-tracked artefact inside `talevia.json`; every criterion field must
     * survive JSON encode → decode without collapse. A forward-compat break
     * (e.g. someone adds a non-nullable field without a default) would fail
     * deserialization here instead of on the first "open an older project"
     * bug report.
     */
    @Test fun lockfileEntrySerializesAndDeserializesWithAllCriterionFields() = runTest {
        val tmpDir = createTempDirectory("lockfile-completeness-serde").toFile()
        val store = ProjectStoreTestKit.create()
        val imageEngine = FullFieldsImageEngine()
        val writer = FakeBlobWriter(tmpDir)

        val registry = ToolRegistry()
        registry.register(toolShimForImage(GenerateImageTool(imageEngine, writer, store)))

        val pid = ProjectId("lockfile-completeness-serde")
        store.upsert(
            "lockfile-completeness-serde",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO),
            ),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("hero"),
                CharacterRefBody(name = "Hero", visualDescription = "red cloak"),
            )
        }

        registry["generate_image"]!!.dispatch(
            buildJsonObject {
                put("prompt", "portrait of the Hero")
                put("seed", 99L)
                put("projectId", pid.value)
                put("consistencyBindingIds", JsonConfig.default.parseToJsonElement("""["hero"]"""))
                put("width", 128)
                put("height", 128)
                put("model", "stub-image-1")
            },
            ctx(),
        )

        val original = store.get(pid)!!.lockfile.entries.single()

        // Round-trip through JSON — this is the same Json instance every
        // bundle/JSONB write path uses (`JsonConfig.default`).
        val encoded = JsonConfig.default.encodeToString(
            io.talevia.core.domain.lockfile.LockfileEntry.serializer(),
            original,
        )
        val decoded = JsonConfig.default.decodeFromString(
            io.talevia.core.domain.lockfile.LockfileEntry.serializer(),
            encoded,
        )

        assertEquals(original.inputHash, decoded.inputHash, "inputHash must survive round-trip")
        assertEquals(
            original.provenance.modelId, decoded.provenance.modelId,
            "modelId must survive round-trip",
        )
        assertEquals(
            original.provenance.modelVersion, decoded.provenance.modelVersion,
            "modelVersion must survive round-trip",
        )
        assertEquals(
            original.provenance.seed, decoded.provenance.seed,
            "seed must survive round-trip",
        )
        assertEquals(
            original.provenance.parameters, decoded.provenance.parameters,
            "parameters map must survive round-trip as a structural JsonObject",
        )
        assertEquals(
            original.sourceContentHashes, decoded.sourceContentHashes,
            "sourceBindingContentHashes must survive round-trip",
        )
        assertEquals(original.assetId, decoded.assetId, "assetId anchor must survive round-trip")
    }
}
