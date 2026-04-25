package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Behavioural coverage for [ReplayLockfileTool]. Uses a real
 * [GenerateImageTool] wired to a counting fake provider so we can prove the
 * replay actually re-calls the engine (cache-bypass) rather than short-
 * circuiting back to the cached asset. Edge cases cover every error arm:
 * missing entry, legacy entry (empty baseInputs), unregistered tool, and a
 * wedge where the target tool fails.
 */
class ReplayLockfileToolTest {

    private val tinyPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * Each call returns the PNG-magic prefix followed by the call counter
     * byte — distinct bytes per invocation, mirroring the original inline
     * fake's contract. Migrated to the shared [CountingImageGenEngine] in
     * cycle 127 (`debt-aigc-test-fake-extract-phase-2`).
     */
    private fun countingImageEngine(): CountingImageGenEngine = CountingImageGenEngine(
        providerId = "fake-img",
        bytesForCall = { call ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, call.toByte())
        },
        fixedModelVersion = "v1",
    )

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

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun freshStore(): FileProjectStore {
        return ProjectStoreTestKit.create()
    }

    @Test fun replaySkipsCacheAndAppendsNewLockfileEntry() = runTest {
        val tmp = createTempDirectory("replay-lockfile-test").toFile()
        val engine = countingImageEngine()

        val writer = FakeBlobWriter(tmp)
        val store = freshStore()
        val projectId = ProjectId("p-replay")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val imageTool = GenerateImageTool(engine, writer, store)
        val registry = ToolRegistry().apply { register(imageTool) }
        val replay = ReplayLockfileTool(registry, store)

        val gen1 = imageTool.execute(
            GenerateImageTool.Input(
                prompt = "a lighthouse",
                seed = 42L,
                projectId = projectId.value,
            ),
            ctx(),
        )
        assertEquals(false, gen1.data.cacheHit)
        assertEquals(1, engine.calls)
        val originalEntry = store.get(projectId)!!.lockfile.entries.single()
        val originalInputHash = originalEntry.inputHash

        // Baseline: a second normal call with the same inputs is a cache hit (no new engine call).
        val cacheHit = imageTool.execute(
            GenerateImageTool.Input(
                prompt = "a lighthouse",
                seed = 42L,
                projectId = projectId.value,
            ),
            ctx(),
        )
        assertEquals(true, cacheHit.data.cacheHit)
        assertEquals(1, engine.calls)
        assertEquals(1, store.get(projectId)!!.lockfile.entries.size)

        // The replay must bypass cache even though an entry exists.
        val replayed = replay.execute(
            ReplayLockfileTool.Input(
                inputHash = originalInputHash,
                projectId = projectId.value,
            ),
            ctx(),
        )

        assertEquals(2, engine.calls, "replay must trigger a fresh engine call (cache-bypassed)")
        val afterEntries = store.get(projectId)!!.lockfile.entries
        assertEquals(2, afterEntries.size, "replay must append a new lockfile entry")
        assertEquals(originalEntry.assetId, afterEntries.first().assetId, "original entry must be untouched")
        assertNotEquals(originalEntry.assetId, afterEntries.last().assetId, "replay produces a new asset id")

        val out = replayed.data
        assertEquals(originalInputHash, out.originalInputHash)
        assertEquals(originalEntry.assetId.value, out.originalAssetId)
        assertEquals(afterEntries.last().assetId.value, out.newAssetId)
        assertEquals("generate_image", out.toolId)
        assertTrue(out.inputHashStable, "inputHash must be stable when source graph is unchanged")
        assertEquals(originalInputHash, out.newInputHash)
    }

    @Test fun missingInputHashErrorsWithoutMutatingLockfile() = runTest {
        val store = freshStore()
        val projectId = ProjectId("p-missing")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        val registry = ToolRegistry()
        val replay = ReplayLockfileTool(registry, store)

        val ex = assertFailsWith<IllegalStateException> {
            replay.execute(
                ReplayLockfileTool.Input(
                    inputHash = "ffffffffffffffff",
                    projectId = projectId.value,
                ),
                ctx(),
            )
        }
        assertTrue("No lockfile entry" in ex.message.orEmpty(), "message should hint the entry is missing; got: ${ex.message}")
        assertEquals(0, store.get(projectId)!!.lockfile.entries.size)
    }

    @Test fun legacyEntryWithEmptyBaseInputsCannotBeReplayed() = runTest {
        val store = freshStore()
        val projectId = ProjectId("p-legacy")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        val seededAsset = AssetId("legacy-asset")
        store.mutate(projectId) { project ->
            project.copy(
                assets = project.assets + MediaAsset(
                    id = seededAsset,
                    source = MediaSource.File("/tmp/legacy.png"),
                    metadata = MediaMetadata(duration = 0.seconds),
                ),
                lockfile = project.lockfile.append(
                    LockfileEntry(
                        inputHash = "deadbeef",
                        toolId = "generate_image",
                        assetId = seededAsset,
                        provenance = GenerationProvenance(
                            providerId = "old",
                            modelId = "m",
                            modelVersion = null,
                            seed = 0,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0,
                        ),
                        baseInputs = JsonObject(emptyMap()),
                    ),
                ),
            )
        }
        val registry = ToolRegistry()
        val replay = ReplayLockfileTool(registry, store)

        val ex = assertFailsWith<IllegalStateException> {
            replay.execute(
                ReplayLockfileTool.Input(inputHash = "deadbeef", projectId = projectId.value),
                ctx(),
            )
        }
        assertTrue(
            "pre-dates baseInputs" in ex.message.orEmpty(),
            "legacy-entry error should call out baseInputs; got: ${ex.message}",
        )
        // No additional lockfile entries appended.
        assertEquals(1, store.get(projectId)!!.lockfile.entries.size)
    }

    @Test fun unregisteredToolErrorsInsteadOfSilentlyDroppingTheReplay() = runTest {
        val store = freshStore()
        val projectId = ProjectId("p-missing-tool")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        val seededAsset = AssetId("orphan-asset")
        store.mutate(projectId) { project ->
            project.copy(
                assets = project.assets + MediaAsset(
                    id = seededAsset,
                    source = MediaSource.File("/tmp/orphan.png"),
                    metadata = MediaMetadata(duration = 0.seconds),
                ),
                lockfile = project.lockfile.append(
                    LockfileEntry(
                        inputHash = "orphanhash",
                        toolId = "generate_image",
                        assetId = seededAsset,
                        provenance = GenerationProvenance(
                            providerId = "old",
                            modelId = "m",
                            modelVersion = null,
                            seed = 0,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0,
                        ),
                        baseInputs = buildJsonObject { put("prompt", JsonPrimitive("x")) },
                    ),
                ),
            )
        }
        // Registry intentionally left empty — the original tool is not wired here.
        val replay = ReplayLockfileTool(ToolRegistry(), store)

        val ex = assertFailsWith<IllegalStateException> {
            replay.execute(
                ReplayLockfileTool.Input(inputHash = "orphanhash", projectId = projectId.value),
                ctx(),
            )
        }
        assertTrue(
            "not registered" in ex.message.orEmpty(),
            "error should call out missing registration; got: ${ex.message}",
        )
    }

    @Test fun forReplayContextPropagatesIsReplayFlag() = runTest {
        // Guards against regressions of ToolContext.forReplay() — the whole
        // cache-bypass story depends on AIGC tools seeing ctx.isReplay=true.
        val baseline = ctx()
        assertEquals(false, baseline.isReplay)
        assertEquals(true, baseline.forReplay().isReplay)
        // Preserves the other fields verbatim.
        assertEquals(baseline.sessionId, baseline.forReplay().sessionId)
        assertEquals(baseline.messageId, baseline.forReplay().messageId)
        assertEquals(baseline.callId, baseline.forReplay().callId)
    }

    @Test fun replayResolvesProjectIdFromSessionContext() = runTest {
        // Validates the ctx.resolveProjectId default path — an agent calling
        // replay_lockfile in a project-bound session shouldn't have to thread
        // projectId through every Input.
        val tmp = createTempDirectory("replay-lockfile-sessioncontext").toFile()
        val engine = countingImageEngine()

        val writer = FakeBlobWriter(tmp)
        val store = freshStore()
        val projectId = ProjectId("p-bound")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val imageTool = GenerateImageTool(engine, writer, store)
        val registry = ToolRegistry().apply { register(imageTool) }
        val replay = ReplayLockfileTool(registry, store)

        imageTool.execute(
            GenerateImageTool.Input(
                prompt = "sessioncontext",
                seed = 7L,
                projectId = projectId.value,
            ),
            ctx(),
        )
        val seedHash = store.get(projectId)!!.lockfile.entries.single().inputHash

        val boundCtx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
            currentProjectId = projectId,
        )

        val out = replay.execute(
            ReplayLockfileTool.Input(inputHash = seedHash, projectId = null),
            boundCtx,
        ).data
        assertEquals(seedHash, out.originalInputHash)
        assertEquals(2, engine.calls)
    }

    @Test fun concurrentReplayAndGenerateOnSameInputHashIsRaceFree() = runTest {
        // `debt-add-runtime-test-replay-lockfile-flow` (cycle 132 backlog):
        // ReplayLockfileTool only had sequential e2e coverage via
        // RefactorLoopE2ETest. The replay path is mutating: it bypasses
        // the inputHash cache, calls the producer tool fresh, appends a
        // new entry. If a concurrent generate_image with the same hash
        // races with the replay, both touch `Project.lockfile.entries`
        // through `ProjectStore.mutate(...)`'s per-project mutex —
        // CLAUDE.md §2 ("Tools mutate Project.timeline via ProjectStore.mutate
        // under a mutex") promises serialization. This test pins that
        // promise: under interleaved dispatch, every call lands its
        // entry, no exception escapes, the lockfile state is consistent.
        //
        // Why this matters: a stale-clip regenerate cascade can fire
        // generate_image AND replay against the same hash within one
        // agent turn (regenerate hits the live path, an explicit
        // ReplayLockfileTool call audits provenance). Without the
        // mutex guarantee these would race the lockfile append; this
        // test is the regression guard if `mutate` ever drops the
        // mutex (e.g. someone "optimizes" it to lock-free). Failure
        // mode is a single dropped entry — the assertion would catch
        // it via `entries.size == 4` (3 replays + 1 cache-hit-after-
        // baseline).
        val tmp = createTempDirectory("replay-concurrent-test").toFile()
        val engine = countingImageEngine()
        val writer = FakeBlobWriter(tmp)
        val store = freshStore()
        val projectId = ProjectId("p-concurrent")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val imageTool = GenerateImageTool(engine, writer, store)
        val registry = ToolRegistry().apply { register(imageTool) }
        val replay = ReplayLockfileTool(registry, store)

        // Seed: one entry to anchor the inputHash both calls will hit.
        val seedInput = GenerateImageTool.Input(
            prompt = "concurrent lighthouse",
            seed = 99L,
            projectId = projectId.value,
        )
        imageTool.execute(seedInput, ctx())
        val seedHash = store.get(projectId)!!.lockfile.entries.single().inputHash
        assertEquals(1, engine.calls)

        // Three concurrent replays + one cache-hit generate_image. The
        // generate_image is a cache hit (engine NOT called); the three
        // replays each force a fresh engine call. Final state must
        // show exactly 1 + 3 = 4 entries with the same inputHash.
        coroutineScope {
            val r1 = async {
                replay.execute(ReplayLockfileTool.Input(inputHash = seedHash, projectId = projectId.value), ctx())
            }
            val r2 = async {
                replay.execute(ReplayLockfileTool.Input(inputHash = seedHash, projectId = projectId.value), ctx())
            }
            val r3 = async {
                replay.execute(ReplayLockfileTool.Input(inputHash = seedHash, projectId = projectId.value), ctx())
            }
            val genCacheHit = async { imageTool.execute(seedInput, ctx()) }
            r1.await()
            r2.await()
            r3.await()
            val cacheHit = genCacheHit.await()
            // The cache-hit call may race against the replays' deletes
            // and re-appends. Either it sees the original entry (cache
            // hit) or it sees one of the freshly-appended replay
            // entries (still a cache hit on the same hash). Both are
            // valid — the assertion is "no fresh engine call from this
            // generate_image", not "saw the original entry".
            assertEquals(true, cacheHit.data.cacheHit)
        }

        // Engine called exactly 4 times: 1 seed + 3 replays. The
        // generate_image cache-hit must NOT have triggered the engine,
        // and no replay's append can be lost — that's the invariant
        // ProjectStore.mutate's mutex guarantees.
        assertEquals(4, engine.calls, "1 seed + 3 replays; cache-hit generate_image adds 0")
        val finalEntries = store.get(projectId)!!.lockfile.entries
        assertEquals(4, finalEntries.size, "every append must survive the race; mutex serializes them")
        // Every entry must share the seedHash — no spurious hash drift.
        assertTrue(
            finalEntries.all { it.inputHash == seedHash },
            "all four entries must share inputHash $seedHash; got: ${finalEntries.map { it.inputHash }}",
        )
        // Every assetId must be unique — replay ALWAYS produces a fresh
        // asset (CountingImageGenEngine returns distinct bytes per call).
        val assetIds = finalEntries.map { it.assetId.value }
        assertEquals(assetIds.size, assetIds.toSet().size, "all asset ids must be distinct; got: $assetIds")
    }
}
