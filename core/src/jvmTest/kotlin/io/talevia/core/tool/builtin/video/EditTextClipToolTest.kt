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
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EditTextClipToolTest {

    private fun ctx(parts: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { parts += it },
        messages = emptyList(),
    )

    private suspend fun fixture(
        style: TextStyle = TextStyle(fontSize = 48f, color = "#FFFFFF"),
        text: String = "hello",
        extraText: Clip.Text? = null,
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        val textClips = listOfNotNull(
            Clip.Text(
                id = ClipId("t-1"),
                timeRange = TimeRange(0.seconds, 2.seconds),
                text = text,
                style = style,
            ),
            extraText,
        )
        val subtitleTrack = Track.Subtitle(id = TrackId("sub"), clips = textClips)
        val videoTrack = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("v-1"),
                    timeRange = TimeRange(0.seconds, 2.seconds),
                    sourceRange = TimeRange(0.seconds, 2.seconds),
                    assetId = AssetId("a-1"),
                ),
            ),
        )
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = listOf(videoTrack, subtitleTrack), duration = 2.seconds)),
        )
        return store to pid
    }

    private fun singleEdit(
        clipId: String,
        newText: String? = null,
        fontFamily: String? = null,
        fontSize: Float? = null,
        color: String? = null,
        backgroundColor: String? = null,
        bold: Boolean? = null,
        italic: Boolean? = null,
    ) = EditTextClipTool.Input(
        projectId = "p",
        items = listOf(
            EditTextClipTool.Item(
                clipId, newText, fontFamily, fontSize, color, backgroundColor, bold, italic,
            ),
        ),
    )

    @Test fun editsTextBody() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        val parts = mutableListOf<Part>()
        val out = tool.execute(singleEdit("t-1", newText = "goodbye"), ctx(parts)).data
        assertEquals(listOf("text"), out.results.single().updatedFields)
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Subtitle>().single().clips
            .filterIsInstance<Clip.Text>().single()
        assertEquals("goodbye", clip.text)
        assertEquals(48f, clip.style.fontSize)
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }

    @Test fun patchesStyleFieldsPreservesOthers() = runTest {
        val (store, pid) = fixture(style = TextStyle(fontSize = 48f, color = "#FFFFFF", bold = false))
        val tool = EditTextClipTool(store)
        val out = tool.execute(
            singleEdit("t-1", fontSize = 72f, bold = true),
            ctx(mutableListOf()),
        ).data
        assertEquals(setOf("fontSize", "bold"), out.results.single().updatedFields.toSet())
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Subtitle>().single().clips
            .filterIsInstance<Clip.Text>().single()
        assertEquals(72f, clip.style.fontSize)
        assertTrue(clip.style.bold)
        assertEquals("#FFFFFF", clip.style.color)
        assertEquals("hello", clip.text)
    }

    @Test fun clearsBackgroundColorWithEmptyString() = runTest {
        val (store, pid) = fixture(style = TextStyle(backgroundColor = "#000000"))
        val tool = EditTextClipTool(store)
        tool.execute(singleEdit("t-1", backgroundColor = ""), ctx(mutableListOf()))
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Subtitle>().single().clips
            .filterIsInstance<Clip.Text>().single()
        assertNull(clip.style.backgroundColor)
    }

    @Test fun setsBackgroundColor() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        tool.execute(singleEdit("t-1", backgroundColor = "#000000"), ctx(mutableListOf()))
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Subtitle>().single().clips
            .filterIsInstance<Clip.Text>().single()
        assertEquals("#000000", clip.style.backgroundColor)
    }

    @Test fun batchEditsMultipleTextClipsAtomically() = runTest {
        val extra = Clip.Text(
            id = ClipId("t-2"),
            timeRange = TimeRange(2.seconds, 2.seconds),
            text = "world",
            style = TextStyle(),
        )
        val (store, pid) = fixture(extraText = extra)
        val tool = EditTextClipTool(store)
        tool.execute(
            EditTextClipTool.Input(
                projectId = pid.value,
                items = listOf(
                    EditTextClipTool.Item("t-1", newText = "a"),
                    EditTextClipTool.Item("t-2", fontSize = 30f),
                ),
            ),
            ctx(mutableListOf()),
        )
        val clips = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Subtitle>().single().clips
            .filterIsInstance<Clip.Text>().associateBy { it.id.value }
        assertEquals("a", clips["t-1"]!!.text)
        assertEquals(30f, clips["t-2"]!!.style.fontSize)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val (store, pid) = fixture()
        val before = store.get(pid)!!
        val tool = EditTextClipTool(store)
        assertFailsWith<IllegalStateException> {
            tool.execute(
                EditTextClipTool.Input(
                    projectId = pid.value,
                    items = listOf(
                        EditTextClipTool.Item("t-1", newText = "x"),
                        EditTextClipTool.Item("ghost", newText = "y"),
                    ),
                ),
                ctx(mutableListOf()),
            )
        }
        assertEquals(before.timeline, store.get(pid)!!.timeline)
    }

    @Test fun rejectsEmptyEdit() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(singleEdit("t-1"), ctx(mutableListOf()))
        }
        assertTrue(ex.message!!.contains("at least one"))
    }

    @Test fun rejectsBlankNewText() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(singleEdit("t-1", newText = "   "), ctx(mutableListOf()))
        }
        assertTrue(ex.message!!.contains("non-blank"))
    }

    @Test fun rejectsNonPositiveFontSize() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(singleEdit("t-1", fontSize = 0f), ctx(mutableListOf()))
        }
        assertTrue(ex.message!!.contains("> 0"))
    }

    @Test fun rejectsNonTextClip() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(singleEdit("v-1", newText = "x"), ctx(mutableListOf()))
        }
        assertTrue(ex.message!!.contains("text"))
    }

    @Test fun rejectsMissingClip() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(singleEdit("nope", newText = "x"), ctx(mutableListOf()))
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun preservesClipIdAndTimeRange() = runTest {
        val (store, pid) = fixture()
        val tool = EditTextClipTool(store)
        tool.execute(singleEdit("t-1", newText = "new"), ctx(mutableListOf()))
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Subtitle>().single().clips
            .filterIsInstance<Clip.Text>().single()
        assertEquals("t-1", clip.id.value)
        assertEquals(TimeRange(0.seconds, 2.seconds), clip.timeRange)
    }
}
