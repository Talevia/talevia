package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
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

/**
 * `set_clip_transform` edits a clip's visual transform in place — the
 * setter sibling of `set_clip_volume` but for opacity / scale / translate /
 * rotate. Tests cover: happy path w/ single field, merging of partial
 * overrides onto an existing transform, normalizing the list to one entry
 * when the clip had none, video + text support, out-of-range rejection
 * (negative scale, opacity>1), no-op rejection, missing-clip fail-loud,
 * filter/sourceBinding/timeRange preservation, and snapshot emission.
 */
class SetClipTransformToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: SetClipTransformTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private suspend fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = SetClipTransformTool(store)
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

    @Test fun setsOpacityOnVideoClipWithNoExistingTransform() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        val out = rig.tool.execute(
            SetClipTransformTool.Input(rig.projectId.value, "c1", opacity = 0.5f),
            rig.ctx,
        )
        assertEquals(Transform(), out.data.oldTransform)
        assertEquals(0.5f, out.data.newTransform.opacity)
        // Unspecified fields retained at default.
        assertEquals(1f, out.data.newTransform.scaleX)
        assertEquals(0f, out.data.newTransform.translateX)

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
        rig.tool.execute(
            SetClipTransformTool.Input(rig.projectId.value, "c1", scaleX = 3f, scaleY = 3f),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        val merged = updated.transforms.single()
        // Overridden fields
        assertEquals(3f, merged.scaleX)
        assertEquals(3f, merged.scaleY)
        // Inherited from the previous transform
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
        rig.tool.execute(
            SetClipTransformTool.Input(rig.projectId.value, "c1", opacity = 0.4f),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        // List is normalised to one entry; base was the first transform.
        assertEquals(1, updated.transforms.size)
        assertEquals(10f, updated.transforms.single().translateX)
        assertEquals(0.4f, updated.transforms.single().opacity)
        // The second transform's scale is NOT inherited (we only read the first).
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
        rig.tool.execute(
            SetClipTransformTool.Input(
                rig.projectId.value,
                "t1",
                scaleX = 0.5f,
                scaleY = 0.5f,
            ),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Text
        assertEquals(0.5f, updated.transforms.single().scaleX)
        assertEquals(0.5f, updated.transforms.single().scaleY)
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
        rig.tool.execute(
            SetClipTransformTool.Input(rig.projectId.value, "c1", rotationDeg = 15f),
            rig.ctx,
        )
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
            rig.tool.execute(
                SetClipTransformTool.Input(rig.projectId.value, "c1"),
                rig.ctx,
            )
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
            rig.tool.execute(
                SetClipTransformTool.Input(rig.projectId.value, "c1", opacity = 1.5f),
                rig.ctx,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                SetClipTransformTool.Input(rig.projectId.value, "c1", opacity = -0.1f),
                rig.ctx,
            )
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
            rig.tool.execute(
                SetClipTransformTool.Input(rig.projectId.value, "c1", scaleX = 0f),
                rig.ctx,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                SetClipTransformTool.Input(rig.projectId.value, "c1", scaleY = -1f),
                rig.ctx,
            )
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
            rig.tool.execute(
                SetClipTransformTool.Input(rig.projectId.value, "ghost", opacity = 0.5f),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun emitsTimelineSnapshotForRevert() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c1")))),
                ),
            ),
        )
        rig.tool.execute(
            SetClipTransformTool.Input(rig.projectId.value, "c1", opacity = 0.25f),
            rig.ctx,
        )
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        val updated = snap.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(0.25f, updated.transforms.single().opacity)
    }
}
