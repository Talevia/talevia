package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.genre.ad.AdNodeKinds
import io.talevia.core.domain.source.genre.musicmv.MusicMvNodeKinds
import io.talevia.core.domain.source.genre.narrative.NarrativeNodeKinds
import io.talevia.core.domain.source.genre.tutorial.TutorialNodeKinds
import io.talevia.core.domain.source.genre.vlog.VlogNodeKinds
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreateProjectFromTemplateToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newStore(): SqlDelightProjectStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightProjectStore(TaleviaDb(driver))
    }

    @Test fun narrativeSeedsSixNodesAndWiresDag() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "My Short",
                template = "narrative",
                projectId = "narr-1",
            ),
            ctx(),
        ).data

        assertEquals("narr-1", out.projectId)
        assertEquals("narrative", out.template)
        assertEquals(6, out.seededNodeIds.size)

        val project = store.get(ProjectId("narr-1"))!!
        val kinds = project.source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.CHARACTER_REF, kinds["protagonist"])
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kinds["style"])
        assertEquals(NarrativeNodeKinds.WORLD, kinds["world-1"])
        assertEquals(NarrativeNodeKinds.STORYLINE, kinds["story-1"])
        assertEquals(NarrativeNodeKinds.SCENE, kinds["scene-1"])
        assertEquals(NarrativeNodeKinds.SHOT, kinds["shot-1"])

        // DAG: editing the style should propagate through world → storyline → scene → shot
        val stale = project.source.byId // confirm graph is intact
        assertTrue(SourceNodeId("world-1") in stale)

        // And the seeded timeline is empty (user fills it in by generating clips)
        assertEquals(0, project.timeline.tracks.sumOf { it.clips.size })
    }

    @Test fun vlogSeedsFourNodes() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Graduation",
                template = "vlog",
                projectId = "vlog-1",
            ),
            ctx(),
        ).data

        assertEquals("vlog-1", out.projectId)
        assertEquals("vlog", out.template)
        assertEquals(4, out.seededNodeIds.size)

        val project = store.get(ProjectId("vlog-1"))!!
        val kinds = project.source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kinds["style"])
        assertEquals(VlogNodeKinds.RAW_FOOTAGE, kinds["footage"])
        assertEquals(VlogNodeKinds.EDIT_INTENT, kinds["intent"])
        assertEquals(VlogNodeKinds.STYLE_PRESET, kinds["style-preset"])
    }

    @Test fun unknownTemplateFailsLoud() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectFromTemplateTool.Input(title = "T", template = "mv"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("mv"))
    }

    @Test fun duplicateProjectIdFailsLoud() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        tool.execute(
            CreateProjectFromTemplateTool.Input(title = "a", template = "narrative", projectId = "p-dup"),
            ctx(),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectFromTemplateTool.Input(title = "b", template = "vlog", projectId = "p-dup"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test fun resolutionAndFpsParsed() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "t",
                template = "vlog",
                projectId = "p-4k",
                resolutionPreset = "4k",
                fps = 24,
            ),
            ctx(),
        ).data
        assertEquals(3840, out.resolutionWidth)
        assertEquals(2160, out.resolutionHeight)
        assertEquals(24, out.fps)
    }

    @Test fun autoSlugFromTitleWhenProjectIdOmitted() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(title = "My Graduation Vlog", template = "vlog"),
            ctx(),
        ).data
        // Slug must be derived from the title (lower-case, hyphenated) and must be non-blank.
        assertTrue(out.projectId.isNotBlank(), "auto-slug must not be blank")
        assertTrue(out.projectId.contains("graduation") || out.projectId.contains("my"), "slug must embed title words")
        assertNotNull(store.get(ProjectId(out.projectId)), "project must be persisted under the auto-slug id")
    }

    @Test fun adSeedsFourNodesAndWiresParents() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Spring Sale",
                template = "ad",
                projectId = "ad-1",
            ),
            ctx(),
        ).data

        assertEquals("ad", out.template)
        assertEquals(4, out.seededNodeIds.size)

        val project = store.get(ProjectId("ad-1"))!!
        val kinds = project.source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.BRAND_PALETTE, kinds["brand-palette"])
        assertEquals(AdNodeKinds.BRAND_BRIEF, kinds["brand-brief"])
        assertEquals(AdNodeKinds.PRODUCT_SPEC, kinds["product"])
        assertEquals(AdNodeKinds.VARIANT_REQUEST, kinds["variant-1"])

        // variant depends on brief + product so DAG propagates edits to either.
        val variant = project.source.byId[SourceNodeId("variant-1")]!!
        val parentIds = variant.parents.map { it.nodeId.value }.toSet()
        assertTrue("brand-brief" in parentIds, "variant must depend on brand-brief")
        assertTrue("product" in parentIds, "variant must depend on product")
    }

    @Test fun musicMvSeedsFourNodesAndSkipsTrack() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Neon Dreams",
                template = "musicmv",
                projectId = "mv-1",
            ),
            ctx(),
        ).data

        assertEquals("musicmv", out.template)
        assertEquals(4, out.seededNodeIds.size)

        val project = store.get(ProjectId("mv-1"))!!
        val kinds = project.source.nodes.map { it.kind }.toSet()
        assertTrue(ConsistencyKinds.BRAND_PALETTE in kinds)
        assertTrue(ConsistencyKinds.CHARACTER_REF in kinds)
        assertTrue(MusicMvNodeKinds.VISUAL_CONCEPT in kinds)
        assertTrue(MusicMvNodeKinds.PERFORMANCE_SHOT in kinds)
        // track is intentionally not seeded — needs an imported music asset.
        assertTrue(MusicMvNodeKinds.TRACK !in kinds, "musicmv.track must not be seeded without an asset")

        // performance_shot depends on both concept + performer.
        val perf = project.source.byId[SourceNodeId("performance-1")]!!
        val parentIds = perf.parents.map { it.nodeId.value }.toSet()
        assertTrue("visual-concept" in parentIds)
        assertTrue("performer" in parentIds)
    }

    @Test fun tutorialSeedsFourNodes() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Setup Guide",
                template = "tutorial",
                projectId = "tut-1",
            ),
            ctx(),
        ).data

        assertEquals("tutorial", out.template)
        assertEquals(4, out.seededNodeIds.size)

        val project = store.get(ProjectId("tut-1"))!!
        val kinds = project.source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kinds["style"])
        assertEquals(TutorialNodeKinds.BRAND_SPEC, kinds["brand-spec"])
        assertEquals(TutorialNodeKinds.SCRIPT, kinds["script"])
        assertEquals(TutorialNodeKinds.BROLL_LIBRARY, kinds["broll"])

        // script depends on style + brand-spec.
        val script = project.source.byId[SourceNodeId("script")]!!
        val parentIds = script.parents.map { it.nodeId.value }.toSet()
        assertTrue("style" in parentIds)
        assertTrue("brand-spec" in parentIds)
    }

    @Test fun titlePreservedInProjectRecord() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val title = "Cinematic Short Film"
        tool.execute(
            CreateProjectFromTemplateTool.Input(title = title, template = "narrative", projectId = "p-title"),
            ctx(),
        )
        val summaries = store.listSummaries()
        val summary = summaries.firstOrNull { it.id == "p-title" }
        assertNotNull(summary, "created project must appear in listSummaries")
        assertEquals(title, summary!!.title, "project title must match the input title")
    }

    // ── template='auto' — intent-driven classification (VISION §5.4 novice path) ──

    @Test fun autoTemplateClassifiesNarrativeFromStoryKeywords() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Short Story",
                template = "auto",
                projectId = "auto-narr",
                intent = "A short film about two friends reconnecting after ten years — a character-driven drama scene.",
            ),
            ctx(),
        ).data

        assertEquals("narrative", out.template)
        assertTrue(out.inferredFromIntent)
        assertNotNull(out.inferredReason)
        // At least one of the narrative keywords (short film / scene / character-driven / drama) hits.
        assertTrue(out.inferredReason!!.contains("narrative"), out.inferredReason)
        // Actually seeded a narrative skeleton.
        val project = store.get(ProjectId("auto-narr"))!!
        assertEquals(6, project.source.nodes.size)
    }

    @Test fun autoTemplateClassifiesMusicMvFromMusicKeyword() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Debut MV",
                template = "auto",
                projectId = "auto-mv",
                intent = "Help me make a music video for my band's new song.",
            ),
            ctx(),
        ).data
        assertEquals("musicmv", out.template)
        assertTrue(out.inferredFromIntent)
    }

    @Test fun autoTemplateClassifiesTutorial() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Sourdough 101",
                template = "auto",
                projectId = "auto-tut",
                intent = "A step-by-step how-to for baking sourdough at home.",
            ),
            ctx(),
        ).data
        assertEquals("tutorial", out.template)
        assertTrue(out.inferredFromIntent)
    }

    @Test fun autoTemplateFallsBackToNarrativeOnEmptySignal() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Mystery Project",
                template = "auto",
                projectId = "auto-fallback",
                // No genre keywords — classifier falls back to narrative.
                intent = "Just something nice for my grandmother.",
            ),
            ctx(),
        ).data
        assertEquals("narrative", out.template)
        assertTrue(out.inferredFromIntent)
        assertTrue(out.inferredReason!!.contains("default", ignoreCase = true), out.inferredReason)
    }

    @Test fun autoTemplateRejectsBlankIntent() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectFromTemplateTool.Input(
                    title = "No Intent",
                    template = "auto",
                    projectId = "auto-blank",
                    intent = "   ",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("intent"), ex.message)
    }

    @Test fun autoTemplateRejectsMissingIntent() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectFromTemplateTool.Input(
                    title = "Missing Intent",
                    template = "auto",
                    projectId = "auto-missing",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("intent"), ex.message)
    }

    @Test fun explicitTemplateIgnoresIntent() = runTest {
        // Intent with strong vlog signal, but explicit template = "ad" still wins
        // — regression guard against auto-mode leaking into explicit calls.
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Explicit Ad",
                template = "ad",
                projectId = "p-explicit",
                intent = "A day in the life vlog",
            ),
            ctx(),
        ).data
        assertEquals("ad", out.template)
        assertEquals(false, out.inferredFromIntent)
        assertEquals(null, out.inferredReason)
    }
}
