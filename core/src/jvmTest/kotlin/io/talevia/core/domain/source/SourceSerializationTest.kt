package io.talevia.core.domain.source

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.genre.vlog.VlogEditIntentBody
import io.talevia.core.domain.source.genre.vlog.VlogNodeKinds
import io.talevia.core.domain.source.genre.vlog.VlogRawFootageBody
import io.talevia.core.domain.source.genre.vlog.VlogStylePresetBody
import io.talevia.core.domain.source.genre.vlog.addVlogEditIntent
import io.talevia.core.domain.source.genre.vlog.addVlogRawFootage
import io.talevia.core.domain.source.genre.vlog.addVlogStylePreset
import io.talevia.core.domain.source.genre.vlog.asVlogEditIntent
import io.talevia.core.domain.source.genre.vlog.asVlogRawFootage
import io.talevia.core.domain.source.genre.vlog.asVlogStylePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialization contract tests for the Source layer.
 *
 * Pins:
 *  - `Source.EMPTY` round-trips with `encodeDefaults = false` (we don't accidentally emit
 *    an empty field explosion).
 *  - Each vlog kind survives a JSON round-trip with body equality.
 *  - A full Project with a populated source + timeline round-trips.
 *  - Backward compat: old Project JSON without a `"source"` key decodes to `Source.EMPTY`.
 */
class SourceSerializationTest {

    private val json = JsonConfig.default

    @Test fun emptySourceRoundTrip() {
        val encoded = json.encodeToString(Source.serializer(), Source.EMPTY)
        val decoded = json.decodeFromString(Source.serializer(), encoded)
        assertEquals(Source.EMPTY, decoded)
        assertEquals(emptyMap(), decoded.byId)
    }

    @Test fun vlogRawFootageNodeRoundTrip() {
        val body = VlogRawFootageBody(assetIds = listOf(AssetId("a-1"), AssetId("a-2")), notes = "graduation morning")
        val src = Source.EMPTY.addVlogRawFootage(SourceNodeId("n-1"), body)

        val encoded = json.encodeToString(Source.serializer(), src)
        val decoded = json.decodeFromString(Source.serializer(), encoded)

        assertEquals(src, decoded)
        val node = decoded.byId[SourceNodeId("n-1")]
        assertNotNull(node)
        assertEquals(VlogNodeKinds.RAW_FOOTAGE, node.kind)
        assertEquals(body, node.asVlogRawFootage())
    }

    @Test fun vlogEditIntentNodeRoundTrip() {
        val body = VlogEditIntentBody(description = "record a warm graduation vlog", targetDurationSeconds = 60, mood = "warm")
        val src = Source.EMPTY.addVlogEditIntent(SourceNodeId("n-intent"), body)

        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        val node = decoded.byId.getValue(SourceNodeId("n-intent"))
        assertEquals(body, node.asVlogEditIntent())
        assertNull(node.asVlogRawFootage(), "typed accessor must return null on kind mismatch")
    }

    @Test fun vlogStylePresetNodeRoundTrip() {
        val body = VlogStylePresetBody(name = "warm-fast", params = mapOf("lut" to "warm", "cut-pace" to "fast"))
        val src = Source.EMPTY.addVlogStylePreset(SourceNodeId("n-style"), body)

        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        assertEquals(body, decoded.byId.getValue(SourceNodeId("n-style")).asVlogStylePreset())
    }

    @Test fun fullProjectWithSourceAndTimelineRoundTrip() {
        val project = Project(
            id = ProjectId("p-vlog"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("v-1")))),
            source = Source.EMPTY
                .addVlogEditIntent(SourceNodeId("intent"), VlogEditIntentBody("sunset vlog"))
                .addVlogStylePreset(SourceNodeId("style"), VlogStylePresetBody("cinematic")),
        )

        val encoded = json.encodeToString(Project.serializer(), project)
        val decoded = json.decodeFromString(Project.serializer(), encoded)

        assertEquals(project, decoded)
        assertEquals(2, decoded.source.nodes.size)
        assertEquals(2L, decoded.source.revision)
    }

    @Test fun preSourceProjectJsonDecodesToEmptySource() {
        // Hand-written legacy payload. Crucially NO "source" key.
        val legacy = """
            {
              "id": "p-old",
              "timeline": { "tracks": [] },
              "assets": []
            }
        """.trimIndent()

        val project = json.decodeFromString(Project.serializer(), legacy)
        assertEquals(ProjectId("p-old"), project.id)
        assertEquals(Source.EMPTY, project.source)
        assertTrue(project.source.nodes.isEmpty())
    }
}
