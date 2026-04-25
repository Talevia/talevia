package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for [AigcBudgetGuard]'s 80% soft warning path.
 *
 * The hard `aigc.budget` ASK at >= cap is covered by [AigcBudgetGuardTest].
 * This file focuses on the new soft signal: a `BusEvent.SpendCapApproaching`
 * fires before the hard threshold so users get a "you're at 80%"
 * heads-up between "no awareness" and "hard stop".
 */
class AigcBudgetGuardWarningTest {

    private val sid = SessionId("s")
    private val pid = ProjectId("p")

    private fun ctx(
        cap: Long?,
        captured: MutableList<BusEvent>,
    ): ToolContext = ToolContext(
        sessionId = sid,
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
        spendCapCents = cap,
        publishEvent = { e -> captured += e },
    )

    private fun entry(cents: Long?, hash: String = "h-$cents"): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId("a-$hash"),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        baseInputs = JsonObject(emptyMap()),
        costCents = cents,
        sessionId = sid.value,
    )

    private suspend fun rig(entries: List<LockfileEntry>) =
        ProjectStoreTestKit.create().also { store ->
            store.upsert("demo", Project(id = pid, timeline = Timeline(), assets = emptyList()))
            if (entries.isNotEmpty()) {
                store.mutate(pid) { project ->
                    project.copy(
                        assets = project.assets + entries.map {
                            MediaAsset(
                                id = it.assetId,
                                source = MediaSource.File("/tmp/${it.assetId.value}.png"),
                                metadata = MediaMetadata(duration = 0.seconds),
                            )
                        },
                        lockfile = entries.fold(project.lockfile) { acc, e -> acc.append(e) },
                    )
                }
            }
        }

    @Test fun underEightyPercentDoesNotEmitWarning() = runTest {
        // 70¢ spend / 100¢ cap = 70% — below the 80% threshold, no event.
        val store = rig(listOf(entry(70L)))
        val captured = mutableListOf<BusEvent>()
        AigcBudgetGuard.enforce("generate_image", store, pid, ctx(cap = 100L, captured))
        assertTrue(
            captured.none { it is BusEvent.SpendCapApproaching },
            "no SpendCapApproaching expected at 70% — got: $captured",
        )
    }

    @Test fun atExactlyEightyPercentEmitsWarning() = runTest {
        // 80¢ / 100¢ — boundary case (>= cap*4/5). Warning fires; hard ASK
        // doesn't (still strictly under cap).
        val store = rig(listOf(entry(80L)))
        val captured = mutableListOf<BusEvent>()
        AigcBudgetGuard.enforce("generate_image", store, pid, ctx(cap = 100L, captured))
        val warn = captured.filterIsInstance<BusEvent.SpendCapApproaching>().single()
        assertEquals(sid, warn.sessionId)
        assertEquals(100L, warn.capCents)
        assertEquals(80L, warn.currentCents)
        assertEquals("aigc", warn.scope)
        assertEquals("generate_image", warn.toolId)
        // ratio is double; check approximate (we test via the ints anyway)
        assertTrue(warn.ratio in 0.79..0.81, "ratio should be ~0.80, got ${warn.ratio}")
    }

    @Test fun betweenEightyAndCapEmitsWarning() = runTest {
        // 95¢ / 100¢ = 95% — well above 80%, still under cap. Warning fires.
        val store = rig(listOf(entry(95L)))
        val captured = mutableListOf<BusEvent>()
        AigcBudgetGuard.enforce("generate_image", store, pid, ctx(cap = 100L, captured))
        val warn = captured.filterIsInstance<BusEvent.SpendCapApproaching>().single()
        assertEquals(95L, warn.currentCents)
    }

    @Test fun atCapDoesNotEmitWarningHardAskInstead() = runTest {
        // 100¢ / 100¢ — hard ASK kicks in; warning path SKIPPED (the
        // `< cap` branch returned without falling through).
        val store = rig(listOf(entry(100L)))
        val captured = mutableListOf<BusEvent>()
        AigcBudgetGuard.enforce("generate_image", store, pid, ctx(cap = 100L, captured))
        assertTrue(
            captured.none { it is BusEvent.SpendCapApproaching },
            "warning must NOT fire at-or-above cap — that's the ASK's job",
        )
    }

    @Test fun nullCapNeverEmitsWarning() = runTest {
        val store = rig(listOf(entry(9999L)))
        val captured = mutableListOf<BusEvent>()
        AigcBudgetGuard.enforce("generate_image", store, pid, ctx(cap = null, captured))
        assertTrue(captured.isEmpty(), "null cap should be a complete no-op")
        assertNull(captured.firstOrNull())
    }

    @Test fun warningFiresEveryQualifyingCallNoDedupeInGuard() = runTest {
        // Per the bullet's design — guard fires every time, subscribers
        // debounce. Confirms no per-session "alreadyWarned" set inside
        // the guard.
        val store = rig(listOf(entry(85L)))
        val captured = mutableListOf<BusEvent>()
        repeat(3) {
            AigcBudgetGuard.enforce("generate_image", store, pid, ctx(cap = 100L, captured))
        }
        assertEquals(
            3, captured.count { it is BusEvent.SpendCapApproaching },
            "guard MUST fire on every qualifying call; deduping is the subscriber's job",
        )
    }
}
