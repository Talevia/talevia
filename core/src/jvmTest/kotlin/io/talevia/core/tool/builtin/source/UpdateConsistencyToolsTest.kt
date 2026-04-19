package io.talevia.core.tool.builtin.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the surgical update_* consistency tools
 * (VISION §5.4). Each test sets up a project, defines a node via the
 * corresponding define_* tool, then exercises the update tool's merge
 * semantics (preservation of unspecified fields, explicit clears,
 * kind guards, missing-node fail-loud, contentHash bump).
 */
class UpdateConsistencyToolsTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val pid: ProjectId,
        val ctx: ToolContext,
    )

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

    // region — character_ref

    @Test fun updateCharacterRefMergesSingleFieldPreservingRest() = runTest {
        val rig = rig()
        DefineCharacterRefTool(rig.store).execute(
            DefineCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair, round glasses, yellow raincoat",
                referenceAssetIds = listOf("asset-1", "asset-2"),
                voiceId = "alloy",
            ),
            rig.ctx,
        )
        val before = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        val beforeBody = before.asCharacterRef()!!

        UpdateCharacterRefTool(rig.store).execute(
            UpdateCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                visualDescription = "crimson hair, round glasses, yellow raincoat",
            ),
            rig.ctx,
        )

        val after = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        val afterBody = after.asCharacterRef()!!
        assertEquals("crimson hair, round glasses, yellow raincoat", afterBody.visualDescription)
        // All other fields preserved.
        assertEquals(beforeBody.name, afterBody.name)
        assertEquals(beforeBody.referenceAssetIds, afterBody.referenceAssetIds)
        assertEquals(beforeBody.voiceId, afterBody.voiceId)
        // contentHash bumped.
        assertNotEquals(before.contentHash, after.contentHash)
    }

    @Test fun updateCharacterRefClearsVoiceIdViaEmptyString() = runTest {
        val rig = rig()
        DefineCharacterRefTool(rig.store).execute(
            DefineCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                voiceId = "alloy",
            ),
            rig.ctx,
        )
        UpdateCharacterRefTool(rig.store).execute(
            UpdateCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                voiceId = "",
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()!!
        assertNull(body.voiceId)
    }

    @Test fun updateCharacterRefClearsLoraPinViaExplicitFlag() = runTest {
        val rig = rig()
        DefineCharacterRefTool(rig.store).execute(
            DefineCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                loraPin = DefineCharacterRefTool.LoraPinInput(
                    adapterId = "lora-mei-v1",
                    weight = 0.8f,
                ),
            ),
            rig.ctx,
        )
        assertNotNull(
            rig.store.get(rig.pid)!!
                .source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()!!.loraPin,
        )
        UpdateCharacterRefTool(rig.store).execute(
            UpdateCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                clearLoraPin = true,
            ),
            rig.ctx,
        )
        assertNull(
            rig.store.get(rig.pid)!!
                .source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()!!.loraPin,
        )
    }

    @Test fun updateCharacterRefReplacesReferenceAssetIds() = runTest {
        val rig = rig()
        DefineCharacterRefTool(rig.store).execute(
            DefineCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                referenceAssetIds = listOf("a1", "a2"),
            ),
            rig.ctx,
        )
        UpdateCharacterRefTool(rig.store).execute(
            UpdateCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                referenceAssetIds = listOf("a3"),
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()!!
        assertEquals(listOf(AssetId("a3")), body.referenceAssetIds)
    }

    @Test fun updateCharacterRefClearsReferenceAssetIdsViaEmptyList() = runTest {
        val rig = rig()
        DefineCharacterRefTool(rig.store).execute(
            DefineCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                referenceAssetIds = listOf("a1"),
            ),
            rig.ctx,
        )
        UpdateCharacterRefTool(rig.store).execute(
            UpdateCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                referenceAssetIds = emptyList(),
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()!!
        assertTrue(body.referenceAssetIds.isEmpty())
    }

    @Test fun updateCharacterRefRejectsEmptyInput() = runTest {
        val rig = rig()
        DefineCharacterRefTool(rig.store).execute(
            DefineCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
            ),
            rig.ctx,
        )
        assertFailsWith<IllegalArgumentException> {
            UpdateCharacterRefTool(rig.store).execute(
                UpdateCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "character-mei",
                ),
                rig.ctx,
            )
        }
    }

    @Test fun updateCharacterRefRejectsMissingNode() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            UpdateCharacterRefTool(rig.store).execute(
                UpdateCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "ghost",
                    visualDescription = "x",
                ),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun updateCharacterRefRejectsKindMismatch() = runTest {
        val rig = rig()
        DefineStyleBibleTool(rig.store).execute(
            DefineStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "cinematic",
            ),
            rig.ctx,
        )
        val ex = assertFailsWith<IllegalStateException> {
            UpdateCharacterRefTool(rig.store).execute(
                UpdateCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "style-warm",
                    visualDescription = "x",
                ),
                rig.ctx,
            )
        }
        assertTrue("character_ref" in ex.message!!, ex.message)
    }

    @Test fun updateCharacterRefRejectsLoraPinAndClearTogether() = runTest {
        val rig = rig()
        DefineCharacterRefTool(rig.store).execute(
            DefineCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
            ),
            rig.ctx,
        )
        assertFailsWith<IllegalArgumentException> {
            UpdateCharacterRefTool(rig.store).execute(
                UpdateCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "character-mei",
                    loraPin = UpdateCharacterRefTool.LoraPinInput(adapterId = "x"),
                    clearLoraPin = true,
                ),
                rig.ctx,
            )
        }
    }

    // endregion

    // region — style_bible

    @Test fun updateStyleBibleMergesMoodKeywordsPreservesDescription() = runTest {
        val rig = rig()
        DefineStyleBibleTool(rig.store).execute(
            DefineStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "cinematic warmth",
                moodKeywords = listOf("warm", "slow"),
            ),
            rig.ctx,
        )
        UpdateStyleBibleTool(rig.store).execute(
            UpdateStyleBibleTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-warm",
                moodKeywords = listOf("warm", "slow", "nostalgic"),
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("style-warm")]!!.asStyleBible()!!
        assertEquals("cinematic warmth", body.description)
        assertEquals(listOf("warm", "slow", "nostalgic"), body.moodKeywords)
    }

    @Test fun updateStyleBibleClearsNegativePromptViaEmptyString() = runTest {
        val rig = rig()
        DefineStyleBibleTool(rig.store).execute(
            DefineStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "x",
                negativePrompt = "no ugly hands",
            ),
            rig.ctx,
        )
        UpdateStyleBibleTool(rig.store).execute(
            UpdateStyleBibleTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-warm",
                negativePrompt = "",
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("style-warm")]!!.asStyleBible()!!
        assertNull(body.negativePrompt)
    }

    @Test fun updateStyleBibleSetsLutReferenceLater() = runTest {
        val rig = rig()
        DefineStyleBibleTool(rig.store).execute(
            DefineStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "x",
            ),
            rig.ctx,
        )
        UpdateStyleBibleTool(rig.store).execute(
            UpdateStyleBibleTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-warm",
                lutReferenceAssetId = "lut-warm-3d",
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("style-warm")]!!.asStyleBible()!!
        assertEquals(AssetId("lut-warm-3d"), body.lutReference)
    }

    @Test fun updateStyleBibleRejectsMissingNode() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            UpdateStyleBibleTool(rig.store).execute(
                UpdateStyleBibleTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "ghost",
                    description = "x",
                ),
                rig.ctx,
            )
        }
    }

    // endregion

    // region — brand_palette

    @Test fun updateBrandPaletteReplacesHexColors() = runTest {
        val rig = rig()
        DefineBrandPaletteTool(rig.store).execute(
            DefineBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF", "#FF3B30"),
            ),
            rig.ctx,
        )
        UpdateBrandPaletteTool(rig.store).execute(
            UpdateBrandPaletteTool.Input(
                projectId = rig.pid.value,
                nodeId = "brand-brand",
                hexColors = listOf("#FF3B30", "#0A84FF"),
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("brand-brand")]!!.asBrandPalette()!!
        assertEquals(listOf("#FF3B30", "#0A84FF"), body.hexColors)
    }

    @Test fun updateBrandPaletteValidatesHexColors() = runTest {
        val rig = rig()
        DefineBrandPaletteTool(rig.store).execute(
            DefineBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        assertFailsWith<IllegalArgumentException> {
            UpdateBrandPaletteTool(rig.store).execute(
                UpdateBrandPaletteTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "brand-brand",
                    hexColors = listOf("not-a-color"),
                ),
                rig.ctx,
            )
        }
    }

    @Test fun updateBrandPaletteRejectsEmptyHexColors() = runTest {
        val rig = rig()
        DefineBrandPaletteTool(rig.store).execute(
            DefineBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        assertFailsWith<IllegalArgumentException> {
            UpdateBrandPaletteTool(rig.store).execute(
                UpdateBrandPaletteTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "brand-brand",
                    hexColors = emptyList(),
                ),
                rig.ctx,
            )
        }
    }

    @Test fun updateBrandPaletteAddsTypographyHintsLater() = runTest {
        val rig = rig()
        DefineBrandPaletteTool(rig.store).execute(
            DefineBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        UpdateBrandPaletteTool(rig.store).execute(
            UpdateBrandPaletteTool.Input(
                projectId = rig.pid.value,
                nodeId = "brand-brand",
                typographyHints = listOf("Inter", "bold headings"),
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("brand-brand")]!!.asBrandPalette()!!
        assertEquals(listOf("Inter", "bold headings"), body.typographyHints)
        // Hex colors preserved.
        assertEquals(listOf("#0A84FF"), body.hexColors)
    }

    // endregion
}
