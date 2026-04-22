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

class SetClipAssetPinnedToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private fun entry(
        inputHash: String,
        assetId: String,
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = 1L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        pinned = pinned,
    )

    private suspend fun seedWithVideoClipAndEntry(
        rig: Rig,
        clipId: String = "c-hero",
        assetId: String = "a-hero",
        inputHash: String = "h-hero",
        pinned: Boolean = false,
    ) {
        var lockfile = Lockfile.EMPTY
        lockfile = lockfile.append(entry(inputHash, assetId, pinned))
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId(clipId),
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId(assetId),
                                ),
                            ),
                        ),
                    ),
                    duration = 2.seconds,
                ),
                lockfile = lockfile,
            ),
        )
    }

    @Test fun pinsEntryForVideoClip() = runTest {
        val rig = rig()
        seedWithVideoClipAndEntry(rig)

        val out = SetClipAssetPinnedTool(rig.store).execute(
            SetClipAssetPinnedTool.Input(projectId = "p", clipId = "c-hero", pinned = true),
            rig.ctx,
        ).data

        assertEquals("c-hero", out.clipId)
        assertEquals("a-hero", out.assetId)
        assertEquals("h-hero", out.inputHash)
        assertEquals("generate_image", out.toolId)
        assertFalse(out.pinnedBefore)
        assertTrue(out.pinnedAfter)
        assertTrue(out.changed)

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.findByInputHash("h-hero")!!.pinned)
    }

    @Test fun pinningAlreadyPinnedClipIsIdempotent() = runTest {
        val rig = rig()
        seedWithVideoClipAndEntry(rig, pinned = true)

        val out = SetClipAssetPinnedTool(rig.store).execute(
            SetClipAssetPinnedTool.Input(projectId = "p", clipId = "c-hero", pinned = true),
            rig.ctx,
        ).data

        assertTrue(out.pinnedBefore)
        assertTrue(out.pinnedAfter)
        assertFalse(out.changed)
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.findByInputHash("h-hero")!!.pinned)
    }

    @Test fun unpinClearsThePin() = runTest {
        val rig = rig()
        seedWithVideoClipAndEntry(rig, pinned = true)

        val out = SetClipAssetPinnedTool(rig.store).execute(
            SetClipAssetPinnedTool.Input(projectId = "p", clipId = "c-hero", pinned = false),
            rig.ctx,
        ).data

        assertTrue(out.pinnedBefore)
        assertFalse(out.pinnedAfter)
        assertTrue(out.changed)
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertFalse(refreshed.lockfile.findByInputHash("h-hero")!!.pinned)
    }

    @Test fun unpinOnUnpinnedClipIsIdempotent() = runTest {
        val rig = rig()
        seedWithVideoClipAndEntry(rig, pinned = false)

        val out = SetClipAssetPinnedTool(rig.store).execute(
            SetClipAssetPinnedTool.Input(projectId = "p", clipId = "c-hero", pinned = false),
            rig.ctx,
        ).data
        assertFalse(out.pinnedBefore)
        assertFalse(out.pinnedAfter)
        assertFalse(out.changed)
    }

    @Test fun textClipFailsLoud() = runTest {
        val rig = rig()
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Subtitle(
                            id = TrackId("sub"),
                            clips = listOf(
                                Clip.Text(
                                    id = ClipId("t-1"),
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    text = "Hello",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            SetClipAssetPinnedTool(rig.store).execute(
                SetClipAssetPinnedTool.Input(projectId = "p", clipId = "t-1", pinned = true),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("text clip"), ex.message)
    }

    @Test fun missingClipFailsLoud() = runTest {
        val rig = rig()
        seedWithVideoClipAndEntry(rig)

        val ex = assertFailsWith<IllegalStateException> {
            SetClipAssetPinnedTool(rig.store).execute(
                SetClipAssetPinnedTool.Input(projectId = "p", clipId = "ghost", pinned = true),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("project_query(select=timeline_clips)"), ex.message)
    }

    @Test fun importedMediaClipFailsLoud() = runTest {
        val rig = rig()
        // Video clip whose asset has NO lockfile entry — simulating imported media.
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c-imported"),
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId("a-imported"),
                                ),
                            ),
                        ),
                    ),
                    duration = 2.seconds,
                ),
                lockfile = Lockfile.EMPTY,
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            SetClipAssetPinnedTool(rig.store).execute(
                SetClipAssetPinnedTool.Input(projectId = "p", clipId = "c-imported", pinned = true),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("no lockfile entry"), ex.message)
        assertTrue(ex.message!!.contains("set_lockfile_entry_pinned"), ex.message)
    }

    @Test fun audioClipAssetIsPinned() = runTest {
        val rig = rig()
        var lockfile = Lockfile.EMPTY
        lockfile = lockfile.append(entry("h-aud", "a-aud"))
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Audio(
                            id = TrackId("a"),
                            clips = listOf(
                                Clip.Audio(
                                    id = ClipId("c-aud"),
                                    timeRange = TimeRange(0.seconds, 3.seconds),
                                    sourceRange = TimeRange(0.seconds, 3.seconds),
                                    assetId = AssetId("a-aud"),
                                ),
                            ),
                        ),
                    ),
                    duration = 3.seconds,
                ),
                lockfile = lockfile,
            ),
        )

        SetClipAssetPinnedTool(rig.store).execute(
            SetClipAssetPinnedTool.Input(projectId = "p", clipId = "c-aud", pinned = true),
            rig.ctx,
        )

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.findByInputHash("h-aud")!!.pinned)
    }
}
