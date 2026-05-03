package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClipActionTransformTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: ClipActionTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = ClipActionTool(store)
        val parts = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { parts += it },
            messages = emptyList(),
        )
        store.upsert("test", project)
        return Rig(store, tool, ctx, project.id, parts)
    }

    private fun videoClip(
        id: String,
        transforms: List<Transform> = emptyList(),
        filters: List<Filter> = emptyList(),
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        transforms = transforms,
        assetId = AssetId("a-$id"),
        filters = filters,
    )

    private fun textClip(id: String): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 3.seconds),
        text = "title",
        style = TextStyle(),
    )

    private fun single(
        clipId: String,
        translateX: Float? = null,
        translateY: Float? = null,
        scaleX: Float? = null,
        scaleY: Float? = null,
        rotationDeg: Float? = null,
        opacity: Float? = null,
    ) = ClipActionTool.Input(
        projectId = "p",
        action = "set_transform",
        transformItems = listOf(
            ClipActionTool.TransformItem(clipId, translateX, translateY, scaleX, scaleY, rotationDeg, opacity),
        ),
    )

    @Test fun setsOpacityOnVideoClipWithNoExistingTransform() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        val out = rig.tool.execute(single("c1", opacity = 0.5f), rig.ctx).data
        val only = out.transformResults.single()
        assertEquals(Transform(), only.oldTransform)
        assertEquals(0.5f, only.newTransform.opacity)
        assertEquals(1f, only.newTransform.scaleX)
        assertEquals(0f, only.newTransform.translateX)
        assertEquals("set_transform", out.action)

        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(listOf(Transform(opacity = 0.5f)), updated.transforms)
    }

    @Test fun mergesOverridesOntoExistingTransform() = runTest {
        val existing = Transform(translateX = 100f, scaleX = 2f, opacity = 0.8f)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(videoClip("c1", transforms = listOf(existing))),
                        ),
                    ),
                ),
            ),
        )
        rig.tool.execute(single("c1", scaleX = 3f, scaleY = 3f), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        val merged = updated.transforms.single()
        assertEquals(3f, merged.scaleX)
        assertEquals(3f, merged.scaleY)
        assertEquals(100f, merged.translateX)
        assertEquals(0.8f, merged.opacity)
    }

    @Test fun normalisesMultiTransformListToSingle() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(
                                videoClip(
                                    "c1",
                                    transforms = listOf(
                                        Transform(translateX = 10f),
                                        Transform(scaleX = 2f),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        rig.tool.execute(single("c1", opacity = 0.4f), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(1, updated.transforms.size)
        assertEquals(10f, updated.transforms.single().translateX)
        assertEquals(0.4f, updated.transforms.single().opacity)
        assertEquals(1f, updated.transforms.single().scaleX)
    }

    @Test fun appliesToTextClip() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Subtitle(TrackId("s1"), listOf(textClip("t1")))),
                ),
            ),
        )
        rig.tool.execute(single("t1", scaleX = 0.5f, scaleY = 0.5f), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Text
        assertEquals(0.5f, updated.transforms.single().scaleX)
        assertEquals(0.5f, updated.transforms.single().scaleY)
    }

    @Test fun batchEditsMultipleClipsAtomically() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(videoClip("c1"), videoClip("c2")),
                        ),
                    ),
                ),
            ),
        )
        rig.tool.execute(
            ClipActionTool.Input(
                projectId = "p",
                action = "set_transform",
                transformItems = listOf(
                    ClipActionTool.TransformItem("c1", opacity = 0.3f),
                    ClipActionTool.TransformItem("c2", scaleX = 2f, scaleY = 2f),
                ),
            ),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Video>().associateBy { it.id.value }
        assertEquals(0.3f, clips["c1"]!!.transforms.single().opacity)
        assertEquals(2f, clips["c2"]!!.transforms.single().scaleX)
    }

    @Test fun emitsOneSnapshotPerBatch() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(videoClip("c1"), videoClip("c2")),
                        ),
                    ),
                ),
            ),
        )
        rig.tool.execute(
            ClipActionTool.Input(
                projectId = "p",
                action = "set_transform",
                transformItems = listOf(
                    ClipActionTool.TransformItem("c1", opacity = 0.3f),
                    ClipActionTool.TransformItem("c2", scaleX = 2f),
                ),
            ),
            rig.ctx,
        )
        val snaps = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>()
        assertEquals(1, snaps.size)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(videoClip("c1", transforms = listOf(Transform(opacity = 1f)))),
                        ),
                    ),
                ),
            ),
        )
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ClipActionTool.Input(
                    projectId = "p",
                    action = "set_transform",
                    transformItems = listOf(
                        ClipActionTool.TransformItem("c1", opacity = 0.3f),
                        ClipActionTool.TransformItem("ghost", opacity = 0.3f),
                    ),
                ),
                rig.ctx,
            )
        }
        assertEquals(before.timeline, rig.store.get(rig.projectId)!!.timeline)
    }

    @Test fun preservesFiltersAndSourceBinding() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(
                                videoClip(
                                    "c1",
                                    filters = listOf(Filter("blur", mapOf("radius" to 3f))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        rig.tool.execute(single("c1", rotationDeg = 15f), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(15f, updated.transforms.single().rotationDeg)
        assertEquals(listOf(Filter("blur", mapOf("radius" to 3f))), updated.filters)
        assertEquals(AssetId("a-c1"), updated.assetId)
        assertEquals(TimeRange(0.seconds, 5.seconds), updated.timeRange)
    }

    @Test fun rejectsAllNullInputs() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1"), rig.ctx)
        }
    }

    @Test fun rejectsOpacityOutOfRange() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", opacity = 1.5f), rig.ctx)
        }
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", opacity = -0.1f), rig.ctx)
        }
    }

    @Test fun rejectsNonPositiveScale() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", scaleX = 0f), rig.ctx)
        }
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", scaleY = -1f), rig.ctx)
        }
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("ghost", opacity = 0.5f), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun rejectsForeignPayload() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ClipActionTool.Input(
                    projectId = "p",
                    action = "set_transform",
                    transformItems = listOf(ClipActionTool.TransformItem("c1", opacity = 0.3f)),
                    volumeItems = listOf(ClipActionTool.VolumeItem("c1", 0.5f)),
                ),
                rig.ctx,
            )
        }
        assertTrue("volumeItems" in ex.message!!, ex.message)
    }
}
