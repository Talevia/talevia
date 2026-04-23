package io.talevia.core.tool.builtin.source

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check that the kind-agnostic source tools — `add_source_node` for
 * create, `update_source_node_body` for body edits, `set_source_node_parents`
 * for parent edits — carry every consistency-node scenario the consistency fold
 * pipeline depends on. Replaces the old `set_character_ref` / `set_style_bible`
 * / `set_brand_palette` trio; those wrappers were removed when the kind-agnostic
 * pair proved sufficient (see
 * docs/decisions/2026-04-22-debt-fold-set-source-node-body-helpers.md).
 */
class SourceToolsTest {

    private suspend fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
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
        val store: FileProjectStore,
        val pid: ProjectId,
        val ctx: ToolContext,
    )

    private fun characterBody(name: String, visualDescription: String, voiceId: String? = null): JsonObject =
        buildJsonObject {
            put("name", name)
            put("visualDescription", visualDescription)
            if (voiceId != null) put("voiceId", voiceId)
        }

    private fun styleBody(
        name: String,
        description: String,
        negativePrompt: String? = null,
        moodKeywords: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        if (negativePrompt != null) put("negativePrompt", negativePrompt)
        if (moodKeywords.isNotEmpty()) {
            put("moodKeywords", buildJsonArray { moodKeywords.forEach { add(it) } })
        }
    }

    private fun brandBody(name: String, hexColors: List<String>, typographyHints: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("name", name)
            put("hexColors", buildJsonArray { hexColors.forEach { add(it) } })
            if (typographyHints.isNotEmpty()) {
                put("typographyHints", buildJsonArray { typographyHints.forEach { add(it) } })
            }
        }

    @Test fun addSourceNodeCreatesCharacterRefThatRoundTripsThroughTypedBody() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)

        val result = add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = slugifyId("Mei", "character"),
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "teal hair, round glasses, yellow raincoat"),
            ),
            rig.ctx,
        )

        assertEquals("character-mei", result.data.nodeId)
        val node = assertNotNull(rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")])
        assertEquals(ConsistencyKinds.CHARACTER_REF, node.kind)
        assertEquals("Mei", node.asCharacterRef()?.name)
    }

    @Test fun updateSourceNodeBodyBumpsContentHashAndReplacesBody() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        val update = UpdateSourceNodeBodyTool(rig.store)

        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "v1"),
            ),
            rig.ctx,
        )
        val before = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!.contentHash

        update.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                body = characterBody("Mei", "v2"),
            ),
            rig.ctx,
        )

        val after = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        assertEquals("v2", after.asCharacterRef()!!.visualDescription)
        assertNotEquals(before, after.contentHash)
        // revision is bumped by replaceNode → bumpedForWrite path.
        assertTrue(after.revision > 0)
    }

    @Test fun characterRefVoiceIdSurvivesCreateAndIsClearedOnFullReplacement() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        val update = UpdateSourceNodeBodyTool(rig.store)

        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "x", voiceId = "nova"),
            ),
            rig.ctx,
        )
        assertEquals("nova", rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()?.voiceId)

        // Full-body replacement without voiceId → the field drops (update_source_node_body is
        // full replacement, not partial patch, per its helpText contract).
        update.execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                body = characterBody("Mei", "x"),
            ),
            rig.ctx,
        )
        assertEquals(null, rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()?.voiceId)
    }

    @Test fun explicitNodeIdBypassesSlugHelper() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        val result = add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "house-style",
                kind = ConsistencyKinds.STYLE_BIBLE,
                body = styleBody(
                    name = "cinematic warm",
                    description = "warm teal/orange, shallow DOF",
                    negativePrompt = "flat lighting",
                    moodKeywords = listOf("warm", "nostalgic"),
                ),
            ),
            rig.ctx,
        )
        assertEquals("house-style", result.data.nodeId)
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("house-style")]!!
        val body = node.asStyleBible()!!
        assertEquals(listOf("warm", "nostalgic"), body.moodKeywords)
        assertEquals("flat lighting", body.negativePrompt)
    }

    @Test fun brandPaletteRoundTripsThroughTypedBody() = runTest {
        val rig = rig()
        val result = AddSourceNodeTool(rig.store).execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = slugifyId("Talevia Brand", "brand"),
                kind = ConsistencyKinds.BRAND_PALETTE,
                body = brandBody(
                    name = "Talevia Brand",
                    hexColors = listOf("#0A84FF", "#FF3B30"),
                    typographyHints = listOf("Inter / geometric sans"),
                ),
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId(result.data.nodeId)]!!
        val body = node.asBrandPalette()!!
        assertEquals(listOf("#0A84FF", "#FF3B30"), body.hexColors)
        assertEquals(listOf("Inter / geometric sans"), body.typographyHints)
    }

    @Test fun addSourceNodeRejectsDuplicateIdEvenAcrossKinds() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "shared",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "v1"),
            ),
            rig.ctx,
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            add.execute(
                AddSourceNodeTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shared",
                    kind = ConsistencyKinds.STYLE_BIBLE,
                    body = styleBody("house", "warm"),
                ),
                rig.ctx,
            )
        }
        assertTrue("already exists" in ex.message!!, ex.message)
    }

    @Test fun sourceQueryFiltersByKindPrefixAndSurfacesContentHash() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "teal hair"),
            ),
            rig.ctx,
        )
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-house",
                kind = ConsistencyKinds.STYLE_BIBLE,
                body = styleBody("house", "warm look"),
            ),
            rig.ctx,
        )

        val tool = SourceQueryTool(rig.store)
        val all = tool.execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value),
            rig.ctx,
        ).data
        assertEquals(2, all.total)

        val onlyChar = tool.execute(
            SourceQueryTool.Input(
                select = "nodes",
                projectId = rig.pid.value,
                kind = ConsistencyKinds.CHARACTER_REF,
            ),
            rig.ctx,
        ).data
        assertEquals(1, onlyChar.returned)
        val charRows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            onlyChar.rows,
        )
        assertEquals("character-mei", charRows.first().id)

        val byPrefix = tool.execute(
            SourceQueryTool.Input(
                select = "nodes",
                projectId = rig.pid.value,
                kindPrefix = "core.consistency.",
            ),
            rig.ctx,
        ).data
        assertEquals(2, byPrefix.returned)
        val prefixRows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            byPrefix.rows,
        )
        assertTrue(prefixRows.all { it.contentHash.isNotEmpty() })
    }

    @Test fun sourceQueryIncludeBodySurfacesFullJson() = runTest {
        val rig = rig()
        AddSourceNodeTool(rig.store).execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "teal hair"),
            ),
            rig.ctx,
        )
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value, includeBody = true),
            rig.ctx,
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
        assertNotNull(rows.first().body)
    }

    @Test fun removeSourceNodeRemovesAndErrorsOnMissing() = runTest {
        val rig = rig()
        AddSourceNodeTool(rig.store).execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "v1"),
            ),
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

    @Test fun foldedPromptPicksUpAddedBindingsEndToEnd() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "teal hair, round glasses"),
            ),
            rig.ctx,
        )
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-warm",
                kind = ConsistencyKinds.STYLE_BIBLE,
                body = styleBody(
                    name = "warm",
                    description = "warm teal/orange",
                    negativePrompt = "flat lighting",
                    moodKeywords = listOf("warm"),
                ),
            ),
            rig.ctx,
        )

        val project = rig.store.get(rig.pid)!!
        val bindings = project.source.resolveConsistencyBindings(
            listOf(SourceNodeId("character-mei"), SourceNodeId("style-warm")),
        )
        val folded = foldConsistencyIntoPrompt("a quiet morning shot", bindings)
        assertTrue(folded.effectivePrompt.contains("warm teal/orange"))
        assertTrue(folded.effectivePrompt.contains("teal hair, round glasses"))
        assertTrue(folded.effectivePrompt.endsWith("a quiet morning shot"))
        assertEquals(listOf("character-mei", "style-warm"), folded.appliedNodeIds.sorted())
        assertTrue(folded.negativePrompt!!.contains("flat lighting"))
    }

    @Test fun addSourceNodeThreadsParentIdsIntoNode() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-warm",
                kind = ConsistencyKinds.STYLE_BIBLE,
                body = styleBody("Warm", "warm grain"),
            ),
            rig.ctx,
        )
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "teal hair"),
                parentIds = listOf("style-warm"),
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        assertEquals(listOf(SourceNodeId("style-warm")), node.parents.map { it.nodeId })
    }

    @Test fun parentEditCascadesStaleDownstream() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "brand-acme",
                kind = ConsistencyKinds.BRAND_PALETTE,
                body = brandBody("Acme", listOf("#0A84FF")),
            ),
            rig.ctx,
        )
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-acmelook",
                kind = ConsistencyKinds.STYLE_BIBLE,
                body = styleBody("AcmeLook", "brand-aligned look"),
                parentIds = listOf("brand-acme"),
            ),
            rig.ctx,
        )
        val hashBefore = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("style-acmelook")]!!.contentHash

        // Patch the parent body via update_source_node_body — child contentHash is keyed on
        // (kind, body, parents) where parents are nodeId refs, so the child's own hash is
        // unchanged. The derivation relationship is expressed via Source.stale() which walks
        // ancestry.
        UpdateSourceNodeBodyTool(rig.store).execute(
            UpdateSourceNodeBodyTool.Input(
                projectId = rig.pid.value,
                nodeId = "brand-acme",
                body = brandBody("Acme", listOf("#FF3B30")),
            ),
            rig.ctx,
        )

        val project = rig.store.get(rig.pid)!!
        val childAfter = project.source.byId[SourceNodeId("style-acmelook")]!!
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
            AddSourceNodeTool(rig.store).execute(
                AddSourceNodeTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "character-mei",
                    kind = ConsistencyKinds.CHARACTER_REF,
                    body = characterBody("Mei", "teal hair"),
                    parentIds = listOf("ghost-parent"),
                ),
                rig.ctx,
            )
        }
        assertTrue("ghost-parent" in ex.message!!, ex.message)
    }

    @Test fun setSourceNodeParentsUpdatesParentListAfterCreate() = runTest {
        val rig = rig()
        val add = AddSourceNodeTool(rig.store)
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-warm",
                kind = ConsistencyKinds.STYLE_BIBLE,
                body = styleBody("Warm", "warm grain"),
            ),
            rig.ctx,
        )
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "brand-acme",
                kind = ConsistencyKinds.BRAND_PALETTE,
                body = brandBody("Acme", listOf("#0A84FF")),
            ),
            rig.ctx,
        )
        add.execute(
            AddSourceNodeTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                kind = ConsistencyKinds.CHARACTER_REF,
                body = characterBody("Mei", "teal hair"),
            ),
            rig.ctx,
        )
        // Attach parents after creation via set_source_node_parents — the kind-agnostic
        // parent-editing path.
        SetSourceNodeParentsTool(rig.store).execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
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
