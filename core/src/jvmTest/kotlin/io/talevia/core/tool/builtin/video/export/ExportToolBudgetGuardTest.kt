package io.talevia.core.tool.builtin.video.export

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [enforceTimelineSpendCap] —
 * `core/tool/builtin/video/export/ExportToolBudgetGuard.kt`.
 * The export-time spend-cap guard. Cycle 196 audit: 86 LOC,
 * indirect coverage via ExportToolBudgetCapTest but the
 * 80%-soft-warning branch + permission-metadata shape were
 * never pinned.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Three early-return arms: null cap / zero total /
 *    under-cap.** Drift in any branch would either
 *    over-prompt (forcing user confirmations on cap-less
 *    sessions or zero-cost timelines) or under-protect.
 *
 * 2. **Marquee 80% soft-warning (`SpendCapApproaching`)
 *    fires under-cap when `total >= cap * 4 / 5`.** Per
 *    impl `cap * 4L / 5L`. Drift to "no warning" would
 *    silently lose the leading-indicator UX; drift to
 *    "warn always" would noise up every export.
 *
 * 3. **Over-cap raises `aigc.budget` permission ASK with
 *    documented metadata; rejection throws with diagnostic.**
 *    Per kdoc: "Why reuse the `aigc.budget` permission
 *    name (not e.g. `media.export.budget`): an existing
 *    user 'Always allow' rule for AIGC dispatches covers
 *    exporting their output too." Drift to a different
 *    permission name would break the cross-surface user
 *    rule.
 */
class ExportToolBudgetGuardTest {

    private fun anyRange() = TimeRange(start = 0.seconds, duration = 1.seconds)

    private fun videoClip(id: String, assetId: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId(assetId),
    )

    private fun lockfileEntry(assetId: String, cents: Long): LockfileEntry = LockfileEntry(
        toolId = "generate_image",
        inputHash = "h-$assetId",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "test",
            modelId = "test",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        costCents = cents,
    )

    private fun project(
        id: String = "p1",
        clips: List<Clip>,
        lockfileEntries: List<LockfileEntry> = emptyList(),
    ): Project = Project(
        id = ProjectId(id),
        timeline = Timeline(
            tracks = if (clips.isEmpty()) emptyList()
            else listOf(Track.Video(id = TrackId("v"), clips = clips)),
        ),
        lockfile = EagerLockfile(entries = lockfileEntries),
    )

    private fun context(
        capCents: Long?,
        decision: PermissionDecision = PermissionDecision.Once,
        capturedRequests: MutableList<PermissionRequest> = mutableListOf(),
        capturedEvents: MutableList<BusEvent> = mutableListOf(),
    ): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { req ->
            capturedRequests += req
            decision
        },
        emitPart = { },
        messages = emptyList(),
        spendCapCents = capCents,
        publishEvent = { e -> capturedEvents += e },
    )

    // ── Early-return arm 1: null cap ─────────────────────────

    @Test fun nullCapPassesThroughSilently() = runTest {
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 1000)),
        )

        // Should NOT throw, NOT publish, NOT ask.
        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = null,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        assertTrue(capturedRequests.isEmpty(), "no permission ask")
        assertTrue(capturedEvents.isEmpty(), "no bus event")
    }

    // ── Early-return arm 2: zero total cost ──────────────────

    @Test fun timelineWithNoAigcContentPassesThroughSilently() = runTest {
        // A timeline with clips but no lockfile entries
        // → all cents are null → totalCost = 0.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = emptyList(),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        assertTrue(capturedRequests.isEmpty())
        assertTrue(capturedEvents.isEmpty())
    }

    @Test fun emptyTimelinePassesThroughSilently() = runTest {
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(clips = emptyList())

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        assertTrue(capturedRequests.isEmpty())
        assertTrue(capturedEvents.isEmpty())
    }

    // ── Early-return arm 3: under-cap (no warning) ──────────

    @Test fun underCapWithLessThan80PercentPassesWithoutWarning() = runTest {
        // 50¢ used, 100¢ cap → 50% < 80% → no warning.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 50)),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        assertTrue(capturedRequests.isEmpty(), "no ask")
        assertTrue(capturedEvents.isEmpty(), "no warning at 50%")
    }

    // ── Marquee 80% soft warning ─────────────────────────────

    @Test fun atEightyPercentSoftWarningFires() = runTest {
        // 80¢ used, 100¢ cap → ratio 0.8 → at threshold.
        // `cap * 4 / 5 = 100 * 4 / 5 = 80` so total >= 80
        // triggers the warning. Drift to ">" instead of
        // ">=" would skip exact-80 cases.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 80)),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        assertTrue(capturedRequests.isEmpty(), "no ask (still under cap)")
        assertEquals(1, capturedEvents.size, "one warning published")
        val event = capturedEvents.single() as BusEvent.SpendCapApproaching
        assertEquals(SessionId("s"), event.sessionId)
        assertEquals(100L, event.capCents)
        assertEquals(80L, event.currentCents)
        assertEquals(0.8, event.ratio)
        assertEquals("export", event.scope)
        assertEquals("export", event.toolId)
    }

    @Test fun above80PercentBelow100PercentSoftWarningStillFires() = runTest {
        // 95¢ used, 100¢ cap → ratio 0.95 → still under-cap
        // (no ask) but warning fires.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 95)),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        assertTrue(capturedRequests.isEmpty(), "still under cap")
        assertEquals(1, capturedEvents.size, "warning at 95%")
        val event = capturedEvents.single() as BusEvent.SpendCapApproaching
        assertEquals(0.95, event.ratio)
    }

    @Test fun justBelow80PercentDoesNotFireWarning() = runTest {
        // 79¢ used, 100¢ cap → 79% < 80% → no warning.
        // (`cap * 4 / 5 = 80` so 79 < 80 → skipped.)
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 79)),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        assertTrue(capturedRequests.isEmpty())
        assertTrue(capturedEvents.isEmpty(), "no warning at 79%")
    }

    // ── Integer-division edge in cap*4/5 ────────────────────

    @Test fun integerDivisionCapTimesFourOverFiveRoundsDown() = runTest {
        // Pin: cap*4/5 uses Long integer division. cap=4
        // → threshold = 4*4/5 = 16/5 = 3 (rounded DOWN).
        // So total=3 triggers the warning even though
        // 3/4 = 75%. Drift to true 80% threshold would
        // miss this pin.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 3)),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 4,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )
        // 3 >= 4*4/5 = 3 → warning fires.
        assertEquals(1, capturedEvents.size, "warning fires at integer-div threshold")
        // ratio = 3.0 / 4.0 = 0.75 → reported as 0.75.
        val event = capturedEvents.single() as BusEvent.SpendCapApproaching
        assertEquals(0.75, event.ratio, "ratio is true 75%, NOT 80%")
    }

    // ── Over-cap → permission ASK + grant flow ──────────────

    @Test fun atCapAsksWithDocumentedAigcBudgetPermission() = runTest {
        // Marquee permission-name pin: per kdoc "Why reuse
        // the `aigc.budget` permission name: an existing
        // user 'Always allow' rule for AIGC dispatches
        // covers exporting their output too." Drift to
        // "media.export.budget" would break cross-surface
        // user rules.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val capturedEvents = mutableListOf<BusEvent>()
        val proj = project(
            id = "test-proj",
            clips = listOf(videoClip("c1", "asset-1"), videoClip("c2", "asset-2")),
            lockfileEntries = listOf(
                lockfileEntry("asset-1", 60),
                lockfileEntry("asset-2", 50),
            ),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                decision = PermissionDecision.Once,
                capturedRequests = capturedRequests,
                capturedEvents = capturedEvents,
            ),
        )

        assertEquals(1, capturedRequests.size, "exactly one permission request")
        val req = capturedRequests.single()
        assertEquals(
            "aigc.budget",
            req.permission,
            "permission name is 'aigc.budget' (NOT 'media.export.budget' or similar)",
        )
        assertEquals(
            "export-exceeded",
            req.pattern,
            "pattern flags this as the exceeded variant",
        )
        // Documented metadata fields all present.
        assertEquals("export", req.metadata["toolId"])
        assertEquals("100", req.metadata["capCents"])
        assertEquals("110", req.metadata["currentCents"], "currentCents = 60+50")
        assertEquals("test-proj", req.metadata["projectId"])
        assertEquals("2", req.metadata["pricedClipCount"])
    }

    @Test fun grantedPermissionAllowsExportToProceed() = runTest {
        // Pin: granted permission → no throw. The handler
        // returns silently and the caller continues.
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 200)),
        )

        // Should NOT throw.
        enforceTimelineSpendCap(
            project = proj,
            ctx = context(capCents = 100, decision = PermissionDecision.Once),
        )
    }

    @Test fun grantedAlwaysPermissionAllowsExportToProceed() = runTest {
        // Pin: PermissionDecision.Always also passes
        // (decision.granted is true for both Once and
        // Always).
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 200)),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(capCents = 100, decision = PermissionDecision.Always),
        )
    }

    // ── Over-cap → rejection throws with diagnostic ─────────

    @Test fun rejectedPermissionThrowsWithDocumentedDiagnostic() = runTest {
        val proj = project(
            id = "p1",
            clips = listOf(videoClip("c1", "asset-1"), videoClip("c2", "asset-2")),
            lockfileEntries = listOf(
                lockfileEntry("asset-1", 600),
                lockfileEntry("asset-2", 500),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            enforceTimelineSpendCap(
                project = proj,
                ctx = context(capCents = 1000, decision = PermissionDecision.Reject),
            )
        }
        // Diagnostic includes total cents + dollars + cap +
        // priced clip count + remediation hints.
        assertTrue("Export refused" in (ex.message ?: ""))
        assertTrue("1100¢" in (ex.message ?: ""), "total cents cited")
        assertTrue("\$11.0" in (ex.message ?: ""), "dollars cited")
        assertTrue("2 priced clip" in (ex.message ?: ""), "priced clip count cited")
        assertTrue("1000¢" in (ex.message ?: ""), "cap cited")
        // Remediation hints.
        assertTrue(
            "session_action(action=set_spend_cap)" in (ex.message ?: ""),
            "raise-cap hint cited; got: ${ex.message}",
        )
        assertTrue(
            "capCents=null" in (ex.message ?: ""),
            "clear-cap hint cited; got: ${ex.message}",
        )
    }

    // ── Mixed priced + unpriced clips ───────────────────────

    @Test fun mixedPricedAndUnpricedClipsCountOnlyPriced() = runTest {
        // Pin: pricedClipCount only counts clips with
        // non-null cents. Unpriced clips contribute 0 to
        // total + 0 to count.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val proj = project(
            clips = listOf(
                videoClip("priced", "asset-priced"),
                videoClip("unpriced", "asset-unpriced"),
            ),
            lockfileEntries = listOf(lockfileEntry("asset-priced", 200)),
        )

        // Triggers ask (200 > 100).
        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                decision = PermissionDecision.Once,
                capturedRequests = capturedRequests,
            ),
        )
        val req = capturedRequests.single()
        assertEquals("1", req.metadata["pricedClipCount"], "only the priced clip counted")
        assertEquals("200", req.metadata["currentCents"], "total = priced clip's cost only")
    }

    // ── Exactly at cap (boundary case) ───────────────────────

    @Test fun exactlyAtCapTriggersAskNotPass() = runTest {
        // Per impl `if (totalCost < cap) return; ... ask`.
        // Boundary: totalCost == cap → totalCost is NOT
        // less than cap → ask path triggered.
        val capturedRequests = mutableListOf<PermissionRequest>()
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 100)),
        )

        enforceTimelineSpendCap(
            project = proj,
            ctx = context(
                capCents = 100,
                decision = PermissionDecision.Once,
                capturedRequests = capturedRequests,
            ),
        )
        assertEquals(1, capturedRequests.size, "exactly-at-cap asks (NOT silent pass)")
        assertEquals("100", capturedRequests.single().metadata["currentCents"])
    }
}
