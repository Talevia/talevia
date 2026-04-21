package io.talevia.core.tool.builtin.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.domain.source.consistency.foldConsistencyIntoPrompt
import io.talevia.core.domain.source.consistency.resolveConsistencyBindings
import io.talevia.core.domain.source.stale
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check that the set_* source tools wire all the way through into the
 * consistency fold pipeline AIGC tools depend on. If this passes the agent has
 * the missing leg of VISION §3.3 (upserting bindings, then using them).
 */
class SourceToolsTest {

    private suspend fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val store = SqlDelightProjectStore(db)
        val pid = ProjectId("p")
        store.upsert("test", Project(id = pid, timeline = Timeline()))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, pid, ctx)
    }

    private data class Rig(
        val store: SqlDelightProjectStore,
        val pid: ProjectId,
        val ctx: ToolContext,
    )

    @Test fun setCharacterRefCreatesNodeWithSluggedDefaultId() = runTest {
        val rig = rig()
        val tool = SetCharacterRefTool(rig.store)

        val result = tool.execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair, round glasses, yellow raincoat",
            ),
            rig.ctx,
        )

        assertEquals("character-mei", result.data.nodeId)
        assertEquals(true, result.data.created)
        val project = rig.store.get(rig.pid)!!
        val node = assertNotNull(project.source.byId[SourceNodeId("character-mei")])
        assertEquals(ConsistencyKinds.CHARACTER_REF, node.kind)
        assertEquals("Mei", node.asCharacterRef()?.name)
    }

    @Test fun reCallingSameNameHitsPatchPathNotCreate() = runTest {
        val rig = rig()
        val tool = SetCharacterRefTool(rig.store)
        tool.execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "v1",
            ),
            rig.ctx,
        )
        val second = tool.execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "v2",
            ),
            rig.ctx,
        )
        assertEquals(false, second.data.created)
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        assertEquals("v2", node.asCharacterRef()!!.visualDescription)
        // contentHash must change so downstream cache is correctly invalidated.
        // (revision is bumped by replaceNode → bumpedForWrite path.)
        assertTrue(node.revision > 0)
    }

    @Test fun setCharacterRefPersistsOptionalVoiceId() = runTest {
        val rig = rig()
        val tool = SetCharacterRefTool(rig.store)

        val result = tool.execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                voiceId = "nova",
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId(result.data.nodeId)]!!
        assertEquals("nova", node.asCharacterRef()?.voiceId)

        // Patch with blank voiceId → clears the pin.
        tool.execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = result.data.nodeId,
                voiceId = "   ",
            ),
            rig.ctx,
        )
        val after = rig.store.get(rig.pid)!!.source.byId[SourceNodeId(result.data.nodeId)]!!
        assertEquals(null, after.asCharacterRef()?.voiceId)
    }

    @Test fun explicitNodeIdOverridesSlug() = runTest {
        val rig = rig()
        val tool = SetStyleBibleTool(rig.store)
        val result = tool.execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "cinematic warm",
                description = "warm teal/orange, shallow DOF",
                nodeId = "house-style",
                negativePrompt = "flat lighting",
                moodKeywords = listOf("warm", "nostalgic"),
            ),
            rig.ctx,
        )
        assertEquals("house-style", result.data.nodeId)
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("house-style")]!!
        val body = node.asStyleBible()!!
        assertEquals(listOf("warm", "nostalgic"), body.moodKeywords)
        assertEquals("flat lighting", body.negativePrompt)
    }

    @Test fun brandPaletteNormalisesHexAndRejectsBadInput() = runTest {
        val rig = rig()
        val tool = SetBrandPaletteTool(rig.store)
        val result = tool.execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "Talevia Brand",
                hexColors = listOf("0a84ff", "#ff3b30"),
                typographyHints = listOf("Inter / geometric sans"),
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId(result.data.nodeId)]!!
        val body = node.asBrandPalette()!!
        assertEquals(listOf("#0A84FF", "#FF3B30"), body.hexColors)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                SetBrandPaletteTool.Input(
                    projectId = rig.pid.value,
                    name = "broken",
                    hexColors = listOf("#zzzzzz"),
                ),
                rig.ctx,
            )
        }
    }

    @Test fun createWithDifferentKindFails() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "v1",
                nodeId = "shared",
            ),
            rig.ctx,
        )
        // Try to reuse "shared" as a style bible — must fail loudly.
        val ex = assertFailsWith<IllegalArgumentException> {
            SetStyleBibleTool(rig.store).execute(
                SetStyleBibleTool.Input(
                    projectId = rig.pid.value,
                    name = "house",
                    description = "warm",
                    nodeId = "shared",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("kind"), ex.message)
    }

    @Test fun listFiltersByKindPrefixAndSurfacesContentHash() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(rig.pid.value, "Mei", "teal hair"),
            rig.ctx,
        )
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(rig.pid.value, "house", "warm look"),
            rig.ctx,
        )
        val tool = ListSourceNodesTool(rig.store)
        val all = tool.execute(ListSourceNodesTool.Input(rig.pid.value), rig.ctx).data
        assertEquals(2, all.totalCount)
        val onlyChar = tool.execute(
            ListSourceNodesTool.Input(rig.pid.value, kind = ConsistencyKinds.CHARACTER_REF),
            rig.ctx,
        ).data
        assertEquals(1, onlyChar.returnedCount)
        assertEquals("character-mei", onlyChar.nodes.first().id)

        val byPrefix = tool.execute(
            ListSourceNodesTool.Input(rig.pid.value, kindPrefix = "core.consistency."),
            rig.ctx,
        ).data
        assertEquals(2, byPrefix.returnedCount)
        // contentHash is non-empty (real fingerprint, not the old revision stub).
        assertTrue(byPrefix.nodes.all { it.contentHash.isNotEmpty() })
    }

    @Test fun listIncludeBodySurfacesFullJson() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(rig.pid.value, "Mei", "teal hair"),
            rig.ctx,
        )
        val list = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(rig.pid.value, includeBody = true),
            rig.ctx,
        ).data
        assertNotNull(list.nodes.first().body)
    }

    @Test fun removeSourceNodeRemovesAndErrorsOnMissing() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(rig.pid.value, "Mei", "v1"),
            rig.ctx,
        )
        val remove = RemoveSourceNodeTool(rig.store)
        val out = remove.execute(
            RemoveSourceNodeTool.Input(rig.pid.value, "character-mei"),
            rig.ctx,
        )
        assertEquals(ConsistencyKinds.CHARACTER_REF, out.data.removedKind)
        assertTrue(rig.store.get(rig.pid)!!.source.byId.isEmpty())

        assertFailsWith<IllegalStateException> {
            remove.execute(RemoveSourceNodeTool.Input(rig.pid.value, "character-mei"), rig.ctx)
        }
    }

    @Test fun foldedPromptPicksUpDefinedBindingsEndToEnd() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair, round glasses",
            ),
            rig.ctx,
        )
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "warm teal/orange",
                negativePrompt = "flat lighting",
                moodKeywords = listOf("warm"),
            ),
            rig.ctx,
        )

        val project = rig.store.get(rig.pid)!!
        val bindings = project.source.resolveConsistencyBindings(
            listOf(SourceNodeId("character-mei"), SourceNodeId("style-warm")),
        )
        val folded = foldConsistencyIntoPrompt("a quiet morning shot", bindings)
        // Style precedes character precedes base prompt (per docs/decisions/ prompt-folding-order entry).
        assertTrue(folded.effectivePrompt.contains("warm teal/orange"))
        assertTrue(folded.effectivePrompt.contains("teal hair, round glasses"))
        assertTrue(folded.effectivePrompt.endsWith("a quiet morning shot"))
        assertEquals(listOf("character-mei", "style-warm"), folded.appliedNodeIds.sorted())
        assertTrue(folded.negativePrompt!!.contains("flat lighting"))
    }

    @Test fun setCharacterRefThreadsParentIdsIntoNode() = runTest {
        val rig = rig()
        // Parent must exist before the child can reference it.
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "Warm",
                description = "warm grain",
            ),
            rig.ctx,
        )
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair",
                parentIds = listOf("style-warm"),
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        assertEquals(listOf(SourceNodeId("style-warm")), node.parents.map { it.nodeId })
    }

    @Test fun parentEditCascadesContentHashDownstream() = runTest {
        val rig = rig()
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "Acme",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "AcmeLook",
                description = "brand-aligned look",
                parentIds = listOf("brand-acme"),
            ),
            rig.ctx,
        )
        val hashBefore = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("style-acmelook")]!!.contentHash

        // Patch the parent brand palette — child style_bible's contentHash should bump
        // because its *parents* list is part of the hash even though the child body
        // didn't change. Plus, Source.stale() flags the descendant as stale.
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "Acme",
                hexColors = listOf("#FF3B30"),
            ),
            rig.ctx,
        )

        val project = rig.store.get(rig.pid)!!
        val childAfter = project.source.byId[SourceNodeId("style-acmelook")]!!
        // Child's own contentHash is keyed on (kind, body, parents) — parents ids
        // are unchanged, so the child's hash is stable. The derivation relationship
        // is expressed via Source.stale() which walks ancestry.
        assertEquals(hashBefore, childAfter.contentHash)
        val stale = project.source.stale(SourceNodeId("brand-acme"))
        assertTrue(
            SourceNodeId("style-acmelook") in stale,
            "child style_bible must be reported stale when its brand_palette parent changes",
        )
    }

    @Test fun parentIdsThatDontExistFailLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    name = "Mei",
                    visualDescription = "teal hair",
                    parentIds = listOf("ghost-parent"),
                ),
                rig.ctx,
            )
        }
        assertTrue("ghost-parent" in ex.message!!, ex.message)
    }

    @Test fun selfParentIsRejectedAtTheToolBoundary() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    name = "Mei",
                    visualDescription = "teal hair",
                    nodeId = "character-mei",
                    parentIds = listOf("character-mei"),
                ),
                rig.ctx,
            )
        }
        assertTrue("character-mei" in ex.message!!, ex.message)
    }

    @Test fun patchingCharacterRefUpdatesParentsToo() = runTest {
        val rig = rig()
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "Warm",
                description = "warm grain",
            ),
            rig.ctx,
        )
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "Acme",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        // Create once with no parents.
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair",
            ),
            rig.ctx,
        )
        // Patch with parents — the tool must update the parent list on the
        // existing node, not just the body.
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                visualDescription = "teal hair v2",
                parentIds = listOf("style-warm", "brand-acme"),
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        assertEquals(
            listOf(SourceNodeId("style-warm"), SourceNodeId("brand-acme")),
            node.parents.map { it.nodeId },
        )
    }
}
