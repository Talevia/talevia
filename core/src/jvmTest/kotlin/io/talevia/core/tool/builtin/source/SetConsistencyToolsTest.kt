package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
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
 * End-to-end tests for the unified set_* consistency tools (VISION §5.2 / §5.4
 * "一等抽象 > patch"). Each tool is a single upsert with patch semantics —
 * create when the node doesn't exist, partial-patch when it does.
 *
 * Covered:
 *  - create path: missing-required failures, slugged default id, explicit id,
 *    value round-trip.
 *  - patch path: per-field null = keep, `""` / `[]` clear semantics,
 *    mutual-exclusion guards, kind-collision guards.
 *  - contentHash bumps so downstream lockfile invalidation stays correct.
 */
class SetConsistencyToolsTest {

    private data class Rig(
        val store: FileProjectStore,
        val pid: ProjectId,
        val ctx: ToolContext,
    )

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

    // region — set_character_ref

    @Test fun setCharacterRefCreatePathReportsCreatedTrue() = runTest {
        val rig = rig()
        val result = SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair",
            ),
            rig.ctx,
        )
        assertEquals(true, result.data.created)
        assertEquals("character-mei", result.data.nodeId)
        assertTrue("name" in result.data.updatedFields)
        assertTrue("visualDescription" in result.data.updatedFields)
    }

    @Test fun setCharacterRefPatchPathReportsCreatedFalse() = runTest {
        val rig = rig()
        val tool = SetCharacterRefTool(rig.store)
        tool.execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair",
            ),
            rig.ctx,
        )
        val patched = tool.execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                visualDescription = "red hair",
            ),
            rig.ctx,
        )
        assertEquals(false, patched.data.created)
        assertEquals(listOf("visualDescription"), patched.data.updatedFields)
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()!!
        assertEquals("red hair", body.visualDescription)
        assertEquals("Mei", body.name) // preserved
    }

    @Test fun setCharacterRefCreateRequiresNameAndVisualDescription() = runTest {
        val rig = rig()
        // Neither name nor visualDescription → fails.
        assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(projectId = rig.pid.value),
                rig.ctx,
            )
        }
        // Only name → fails (still missing visualDescription).
        assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(projectId = rig.pid.value, name = "Mei"),
                rig.ctx,
            )
        }
    }

    @Test fun setCharacterRefRejectsBlankWhenProvided() = runTest {
        val rig = rig()
        assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    name = "   ",
                    visualDescription = "x",
                ),
                rig.ctx,
            )
        }
    }

    @Test fun setCharacterRefPatchWithNoFieldsFails() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
            ),
            rig.ctx,
        )
        // Node exists, but no body fields supplied → "nothing to update".
        val ex = assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "character-mei",
                ),
                rig.ctx,
            )
        }
        assertTrue("already exists" in ex.message!!, ex.message)
    }

    @Test fun setCharacterRefPatchClearsVoiceIdViaEmptyString() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                voiceId = "alloy",
            ),
            rig.ctx,
        )
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
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

    @Test fun setCharacterRefClearLoraPinFlag() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                loraPin = SetCharacterRefTool.LoraPinInput(adapterId = "lora-mei-v1", weight = 0.8f),
            ),
            rig.ctx,
        )
        assertNotNull(
            rig.store.get(rig.pid)!!
                .source.byId[SourceNodeId("character-mei")]!!.asCharacterRef()!!.loraPin,
        )
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
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

    @Test fun setCharacterRefRejectsLoraPinAndClearTogether() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
            ),
            rig.ctx,
        )
        assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "character-mei",
                    loraPin = SetCharacterRefTool.LoraPinInput(adapterId = "x"),
                    clearLoraPin = true,
                ),
                rig.ctx,
            )
        }
    }

    @Test fun setCharacterRefReplacesReferenceAssetIds() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                referenceAssetIds = listOf("a1", "a2"),
            ),
            rig.ctx,
        )
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
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

    @Test fun setCharacterRefClearsReferenceAssetIdsViaEmptyList() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "x",
                referenceAssetIds = listOf("a1"),
            ),
            rig.ctx,
        )
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
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

    @Test fun setCharacterRefKindCollisionFailsLoud() = runTest {
        val rig = rig()
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "cinematic",
            ),
            rig.ctx,
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            SetCharacterRefTool(rig.store).execute(
                SetCharacterRefTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "style-warm",
                    visualDescription = "x",
                ),
                rig.ctx,
            )
        }
        assertTrue("kind" in ex.message!!, ex.message)
    }

    @Test fun setCharacterRefPatchBumpsContentHash() = runTest {
        val rig = rig()
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                name = "Mei",
                visualDescription = "teal hair",
            ),
            rig.ctx,
        )
        val before = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        SetCharacterRefTool(rig.store).execute(
            SetCharacterRefTool.Input(
                projectId = rig.pid.value,
                nodeId = "character-mei",
                visualDescription = "red hair",
            ),
            rig.ctx,
        )
        val after = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("character-mei")]!!
        assertNotEquals(before.contentHash, after.contentHash)
    }

    // endregion

    // region — set_style_bible

    @Test fun setStyleBibleCreateThenPatchMergesFields() = runTest {
        val rig = rig()
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "cinematic warmth",
                moodKeywords = listOf("warm", "slow"),
            ),
            rig.ctx,
        )
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                nodeId = "style-warm",
                moodKeywords = listOf("warm", "slow", "nostalgic"),
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("style-warm")]!!.asStyleBible()!!
        assertEquals("cinematic warmth", body.description) // preserved
        assertEquals(listOf("warm", "slow", "nostalgic"), body.moodKeywords)
    }

    @Test fun setStyleBibleCreateRequiresNameAndDescription() = runTest {
        val rig = rig()
        assertFailsWith<IllegalArgumentException> {
            SetStyleBibleTool(rig.store).execute(
                SetStyleBibleTool.Input(projectId = rig.pid.value, name = "warm"),
                rig.ctx,
            )
        }
    }

    @Test fun setStyleBibleClearsNegativePromptViaEmptyString() = runTest {
        val rig = rig()
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "x",
                negativePrompt = "no ugly hands",
            ),
            rig.ctx,
        )
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
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

    @Test fun setStyleBibleSetsLutReferenceLater() = runTest {
        val rig = rig()
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
                projectId = rig.pid.value,
                name = "warm",
                description = "x",
            ),
            rig.ctx,
        )
        SetStyleBibleTool(rig.store).execute(
            SetStyleBibleTool.Input(
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

    // endregion

    // region — set_brand_palette

    @Test fun setBrandPaletteCreateRequiresNameAndHexColors() = runTest {
        val rig = rig()
        assertFailsWith<IllegalArgumentException> {
            SetBrandPaletteTool(rig.store).execute(
                SetBrandPaletteTool.Input(projectId = rig.pid.value, name = "brand"),
                rig.ctx,
            )
        }
    }

    @Test fun setBrandPaletteReplacesHexColorsOnPatch() = runTest {
        val rig = rig()
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF", "#FF3B30"),
            ),
            rig.ctx,
        )
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
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

    @Test fun setBrandPaletteRejectsEmptyHexColorsOnPatch() = runTest {
        val rig = rig()
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        assertFailsWith<IllegalArgumentException> {
            SetBrandPaletteTool(rig.store).execute(
                SetBrandPaletteTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "brand-brand",
                    hexColors = emptyList(),
                ),
                rig.ctx,
            )
        }
    }

    @Test fun setBrandPaletteValidatesHexColorFormat() = runTest {
        val rig = rig()
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        assertFailsWith<IllegalArgumentException> {
            SetBrandPaletteTool(rig.store).execute(
                SetBrandPaletteTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "brand-brand",
                    hexColors = listOf("not-a-color"),
                ),
                rig.ctx,
            )
        }
    }

    @Test fun setBrandPaletteAddsTypographyHintsLater() = runTest {
        val rig = rig()
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                name = "brand",
                hexColors = listOf("#0A84FF"),
            ),
            rig.ctx,
        )
        SetBrandPaletteTool(rig.store).execute(
            SetBrandPaletteTool.Input(
                projectId = rig.pid.value,
                nodeId = "brand-brand",
                typographyHints = listOf("Inter", "bold headings"),
            ),
            rig.ctx,
        )
        val body = rig.store.get(rig.pid)!!
            .source.byId[SourceNodeId("brand-brand")]!!.asBrandPalette()!!
        assertEquals(listOf("Inter", "bold headings"), body.typographyHints)
        assertEquals(listOf("#0A84FF"), body.hexColors)
    }

    // endregion
}
