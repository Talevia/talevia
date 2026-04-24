package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for the merged [ProjectPinActionTool] (replaces the
 * `set_clip_asset_pinned` + `set_lockfile_entry_pinned` pair).
 *
 * Axes covered (§3a #9):
 *  - target=clip, happy path: flips pinned, changed=true, idempotent replay.
 *  - target=clip, text clip: fails with "text clip" diagnostic.
 *  - target=clip, unknown clip id: fails with lookup hint.
 *  - target=clip, no lockfile entry: fails with "use target=lockfile_entry" hint.
 *  - target=lockfile_entry, happy path + idempotency.
 *  - target=lockfile_entry, unknown inputHash: fails with query hint.
 *  - Reject-field matrix: target=clip + inputHash rejected; target=lockfile_entry
 *    + clipId rejected; target=clip without clipId rejected; target=lockfile_entry
 *    without inputHash rejected.
 *  - Unknown target: fails loud with enum enumeration.
 */
class ProjectPinActionToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun entry(
        hash: String,
        assetId: String,
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "gpt-image-1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        pinned = pinned,
    )

    private fun videoClip(id: String, assetId: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId(assetId),
    )

    private suspend fun fixture(
        clips: List<Clip> = emptyList(),
        lockfileEntries: List<LockfileEntry> = emptyList(),
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        val timeline = if (clips.isEmpty()) {
            Timeline()
        } else {
            Timeline(tracks = listOf(Track.Video(id = TrackId("t"), clips = clips)))
        }
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = timeline,
                lockfile = Lockfile(entries = lockfileEntries),
            ),
        )
        return store to pid
    }

    private suspend fun current(store: FileProjectStore, pid: ProjectId, inputHash: String): LockfileEntry =
        store.get(pid)!!.lockfile.findByInputHash(inputHash) ?: error("entry not found")

    @Test fun clipTargetFlipsPinnedAndIsIdempotent() = runTest {
        val (store, pid) = fixture(
            clips = listOf(videoClip("c-hero", "a-hero")),
            lockfileEntries = listOf(entry("h-hero", "a-hero")),
        )
        val tool = ProjectPinActionTool(store)

        val first = tool.execute(
            ProjectPinActionTool.Input(
                projectId = pid.value, target = "clip", clipId = "c-hero", pinned = true,
            ),
            ctx(),
        ).data
        assertEquals("clip", first.target)
        assertEquals("c-hero", first.clipId)
        assertEquals("h-hero", first.inputHash)
        assertEquals("a-hero", first.assetId)
        assertFalse(first.pinnedBefore)
        assertTrue(first.pinnedAfter)
        assertTrue(first.changed)
        assertTrue(current(store, pid, "h-hero").pinned, "project state must reflect pin")

        val replay = tool.execute(
            ProjectPinActionTool.Input(
                projectId = pid.value, target = "clip", clipId = "c-hero", pinned = true,
            ),
            ctx(),
        ).data
        assertFalse(replay.changed, "idempotent — same value is a no-op")
        assertTrue(replay.pinnedAfter)
    }

    @Test fun lockfileEntryTargetFlipsPinnedAndIsIdempotent() = runTest {
        val (store, pid) = fixture(
            lockfileEntries = listOf(entry("h-direct", "a-direct")),
        )
        val tool = ProjectPinActionTool(store)

        val pinned = tool.execute(
            ProjectPinActionTool.Input(
                projectId = pid.value, target = "lockfile_entry", inputHash = "h-direct", pinned = true,
            ),
            ctx(),
        ).data
        assertEquals("lockfile_entry", pinned.target)
        assertEquals(null, pinned.clipId)
        assertEquals("h-direct", pinned.inputHash)
        assertEquals("a-direct", pinned.assetId)
        assertTrue(pinned.changed)
        assertTrue(pinned.pinnedAfter)
        assertTrue(current(store, pid, "h-direct").pinned)

        val unpinned = tool.execute(
            ProjectPinActionTool.Input(
                projectId = pid.value, target = "lockfile_entry", inputHash = "h-direct", pinned = false,
            ),
            ctx(),
        ).data
        assertTrue(unpinned.changed, "unpin on a pinned entry must flip")
        assertFalse(unpinned.pinnedAfter)
        assertFalse(current(store, pid, "h-direct").pinned)
    }

    @Test fun clipTargetWithTextClipFailsLoud() = runTest {
        val textClip = Clip.Text(
            id = ClipId("c-text"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            text = "caption",
        )
        val (store, pid) = fixture(clips = listOf(textClip))
        val ex = assertFailsWith<IllegalStateException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "clip", clipId = "c-text", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue("text clip" in ex.message!!, "diagnostic must name text-clip nature: ${ex.message}")
    }

    @Test fun clipTargetWithUnknownClipFailsLoud() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "clip", clipId = "ghost", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue(
            "not found" in ex.message!! && "project_query" in ex.message!!,
            "diagnostic must name the clip and suggest discovery: ${ex.message}",
        )
    }

    @Test fun clipTargetWithNoLockfileEntryPointsAtLockfileTarget() = runTest {
        val (store, pid) = fixture(
            clips = listOf(videoClip("c-imported", "a-imported")),
            // No lockfile entry — imported media case.
        )
        val ex = assertFailsWith<IllegalStateException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "clip", clipId = "c-imported", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue(
            "target=lockfile_entry" in ex.message!!,
            "diagnostic must point at the direct-hash escape hatch: ${ex.message}",
        )
    }

    @Test fun lockfileEntryTargetWithUnknownHashFailsLoud() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "lockfile_entry", inputHash = "h-missing", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue("not found" in ex.message!!, ex.message)
        assertTrue("project_query" in ex.message!!, "hint points at discovery: ${ex.message}")
    }

    @Test fun clipTargetRejectsInputHash() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "clip", clipId = "c", inputHash = "h", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue("inputHash" in ex.message!!, "reject diagnostic must name inputHash: ${ex.message}")
    }

    @Test fun lockfileEntryTargetRejectsClipId() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "lockfile_entry", inputHash = "h", clipId = "c", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue("clipId" in ex.message!!, "reject diagnostic must name clipId: ${ex.message}")
    }

    @Test fun clipTargetWithoutClipIdFailsLoud() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "clip", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue("clipId" in ex.message!!, ex.message)
    }

    @Test fun lockfileEntryTargetWithoutHashFailsLoud() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "lockfile_entry", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue("inputHash" in ex.message!!, ex.message)
    }

    @Test fun unknownTargetFailsLoud() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectPinActionTool(store).execute(
                ProjectPinActionTool.Input(
                    projectId = pid.value, target = "mystery", pinned = true,
                ),
                ctx(),
            )
        }
        assertTrue("clip" in ex.message!! && "lockfile_entry" in ex.message!!, "diagnostic must enumerate valid targets: ${ex.message}")
    }
}
