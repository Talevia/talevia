package io.talevia.core.tool.builtin.project

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [executeSetOutputProfile] —
 * `core/tool/builtin/project/SetOutputProfileHandler.kt`. The
 * `project_action(kind="lifecycle", args={action="set_output_profile"})`
 * partial-override output-profile patcher. Cycle 209 audit: 115 LOC, 0
 * direct test refs.
 *
 * Same audit-pattern fallback as cycles 207-208. Sibling structurally
 * to cycle 208's `ClipSetTransformHandlerTest` (partial-override + per-
 * field range validation) — this handler operates on
 * `Project.outputProfile` instead of `Clip.transforms` but otherwise
 * shares the validation / partial-merge / change-detection shape.
 *
 * Six correctness contracts pinned:
 *
 *  1. **`projectId` required + at-least-one field required.** Drift to
 *     "null projectId is OK" or "all-null silently no-ops" would
 *     either crash on a phantom mutate or silently swallow agent
 *     intent.
 *
 *  2. **Width/height must come paired.** Setting one without the other
 *     fails fast (invalid intermediate state). Drift to "fill missing
 *     dimension from current profile" would silently aspect-distort
 *     on every partial edit.
 *
 *  3. **Per-field positivity + non-blankness.** All numeric fields
 *     `> 0`; codec / container strings non-blank. Tested against zero
 *     and negative values + empty / whitespace-only strings.
 *
 *  4. **Partial-override semantics.** Unspecified fields inherit from
 *     `current.outputProfile`. Marquee invariant — drift to "missing
 *     field resets to default" (`OutputProfile.DEFAULT_1080P`) would
 *     silently wipe a project's prior bitrate / codec on every
 *     resolution edit.
 *
 *  5. **`updatedFields` reflects ACTUAL change, not "specified".** A
 *     request like `videoCodec="h264"` when current is already h264
 *     produces an empty `updatedFields` AND the "unchanged" summary
 *     phrasing. Drift to "every specified field counts as updated"
 *     would lie about the project state.
 *
 *  6. **Only `OutputProfile` mutates.** The timeline's authoring
 *     resolution / frameRate is intentionally untouched (different
 *     concern: output = render spec, timeline = authoring grid).
 *     Drift to "update timeline.resolution too" would force every
 *     export-spec edit to reflow the timeline.
 *
 * Plus shape pins: `fps` round-trip via FrameRate(num, 1); summary
 * format; title format `"set output profile <pid>"`; mutation
 * actually persists via `projects.mutate`.
 */
class SetOutputProfileHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun input(
        projectId: String? = null,
        resolutionWidth: Int? = null,
        resolutionHeight: Int? = null,
        fps: Int? = null,
        videoCodec: String? = null,
        audioCodec: String? = null,
        videoBitrate: Long? = null,
        audioBitrate: Long? = null,
        container: String? = null,
    ): ProjectLifecycleActionTool.Input = ProjectLifecycleActionTool.Input(
        action = "set_output_profile",
        projectId = projectId,
        resolutionWidth = resolutionWidth,
        resolutionHeight = resolutionHeight,
        fps = fps,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        videoBitrate = videoBitrate,
        audioBitrate = audioBitrate,
        container = container,
    )

    // ── 1. Required-input rejection ─────────────────────────

    @Test fun missingProjectIdThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeSetOutputProfile(store, input(projectId = null, fps = 30), ctx)
        }
        assertTrue(
            "requires `projectId`" in (ex.message ?: ""),
            "expected 'requires projectId'; got: ${ex.message}",
        )
    }

    @Test fun allNullFieldsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val ex = assertFailsWith<IllegalArgumentException> {
            executeSetOutputProfile(store, input(projectId = created.id.value), ctx)
        }
        assertTrue(
            "at least one field must be provided" in (ex.message ?: ""),
            "expected 'at least one field' message; got: ${ex.message}",
        )
    }

    // ── 2. Width/height must come paired ────────────────────

    @Test fun widthWithoutHeightRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val ex = assertFailsWith<IllegalArgumentException> {
            executeSetOutputProfile(
                store,
                input(projectId = created.id.value, resolutionWidth = 1280),
                ctx,
            )
        }
        val msg = ex.message ?: ""
        assertTrue("must be provided together" in msg, "expected pair-required message; got: $msg")
        assertTrue("width=1280" in msg, "expected width cited; got: $msg")
        assertTrue("height=null" in msg, "expected null-height cited; got: $msg")
    }

    @Test fun heightWithoutWidthRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val ex = assertFailsWith<IllegalArgumentException> {
            executeSetOutputProfile(
                store,
                input(projectId = created.id.value, resolutionHeight = 720),
                ctx,
            )
        }
        assertTrue("must be provided together" in (ex.message ?: ""))
    }

    // ── 3. Per-field positivity + non-blankness ─────────────

    @Test fun nonPositiveResolutionRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        for (bad in listOf(0, -1, -1920)) {
            val exW = assertFailsWith<IllegalArgumentException> {
                executeSetOutputProfile(
                    store,
                    input(projectId = created.id.value, resolutionWidth = bad, resolutionHeight = 720),
                    ctx,
                )
            }
            assertTrue(
                "resolutionWidth must be > 0" in (exW.message ?: ""),
                "expected width > 0 message for $bad; got: ${exW.message}",
            )
            val exH = assertFailsWith<IllegalArgumentException> {
                executeSetOutputProfile(
                    store,
                    input(projectId = created.id.value, resolutionWidth = 1280, resolutionHeight = bad),
                    ctx,
                )
            }
            assertTrue(
                "resolutionHeight must be > 0" in (exH.message ?: ""),
                "expected height > 0 message for $bad; got: ${exH.message}",
            )
        }
    }

    @Test fun nonPositiveNumericFieldsRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        for ((field, lambda) in listOf<Pair<String, (Int) -> ProjectLifecycleActionTool.Input>>(
            "fps" to { v -> input(projectId = created.id.value, fps = v) },
        )) {
            for (bad in listOf(0, -1)) {
                val ex = assertFailsWith<IllegalArgumentException> {
                    executeSetOutputProfile(store, lambda(bad), ctx)
                }
                assertTrue(
                    "$field must be > 0" in (ex.message ?: ""),
                    "expected '$field must be > 0' for $bad; got: ${ex.message}",
                )
            }
        }
        // Bitrates use Long.
        for (bad in listOf(0L, -1L)) {
            val exV = assertFailsWith<IllegalArgumentException> {
                executeSetOutputProfile(
                    store,
                    input(projectId = created.id.value, videoBitrate = bad),
                    ctx,
                )
            }
            assertTrue("videoBitrate must be > 0" in (exV.message ?: ""))
            val exA = assertFailsWith<IllegalArgumentException> {
                executeSetOutputProfile(
                    store,
                    input(projectId = created.id.value, audioBitrate = bad),
                    ctx,
                )
            }
            assertTrue("audioBitrate must be > 0" in (exA.message ?: ""))
        }
    }

    @Test fun blankCodecAndContainerRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        for (bad in listOf("", " ", "\t")) {
            val exV = assertFailsWith<IllegalArgumentException> {
                executeSetOutputProfile(
                    store,
                    input(projectId = created.id.value, videoCodec = bad),
                    ctx,
                )
            }
            assertTrue("videoCodec must not be blank" in (exV.message ?: ""))
            val exA = assertFailsWith<IllegalArgumentException> {
                executeSetOutputProfile(
                    store,
                    input(projectId = created.id.value, audioCodec = bad),
                    ctx,
                )
            }
            assertTrue("audioCodec must not be blank" in (exA.message ?: ""))
            val exC = assertFailsWith<IllegalArgumentException> {
                executeSetOutputProfile(
                    store,
                    input(projectId = created.id.value, container = bad),
                    ctx,
                )
            }
            assertTrue("container must not be blank" in (exC.message ?: ""))
        }
    }

    // ── Project-not-found ──────────────────────────────────

    @Test fun missingProjectFailsLoud() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeSetOutputProfile(store, input(projectId = "ghost", fps = 30), ctx)
        }
        assertTrue(
            "project ghost not found" in (ex.message ?: ""),
            "expected projectId in error; got: ${ex.message}",
        )
    }

    // ── 4. Partial-override semantics (marquee) ─────────────

    @Test fun partialOverrideInheritsUnspecifiedFromCurrent() = runTest {
        // Marquee partial-override pin: a request setting only `fps`
        // must leave videoCodec / audioCodec / videoBitrate /
        // audioBitrate / container at their prior values.
        val customProfile = OutputProfile(
            resolution = Resolution(2560, 1440),
            frameRate = FrameRate.FPS_60,
            videoCodec = "h265",
            audioCodec = "opus",
            videoBitrate = 12_000_000,
            audioBitrate = 256_000,
            container = "mkv",
        )
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        store.mutate(created.id) { p -> p.copy(outputProfile = customProfile) }

        val result = executeSetOutputProfile(
            store,
            input(projectId = created.id.value, fps = 24),
            ctx,
        )
        val out = result.data!!.setOutputProfileResult!!
        assertEquals(24, out.fps, "fps overridden")
        assertEquals(2560, out.resolutionWidth, "resolution width inherited")
        assertEquals(1440, out.resolutionHeight, "resolution height inherited")
        assertEquals("h265", out.videoCodec, "videoCodec inherited")
        assertEquals("opus", out.audioCodec, "audioCodec inherited")
        assertEquals(12_000_000L, out.videoBitrate, "videoBitrate inherited")
        assertEquals(256_000L, out.audioBitrate, "audioBitrate inherited")
        assertEquals("mkv", out.container, "container inherited")
        assertEquals(listOf("frameRate"), out.updatedFields, "only frameRate counted as changed")
    }

    @Test fun multiFieldOverrideAllReportedInUpdatedFields() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        // Project starts at OutputProfile.DEFAULT_1080P.
        val result = executeSetOutputProfile(
            store,
            input(
                projectId = created.id.value,
                resolutionWidth = 3840,
                resolutionHeight = 2160,
                fps = 24,
                videoCodec = "h265",
                videoBitrate = 50_000_000L,
            ),
            ctx,
        )
        val r = result.data!!.setOutputProfileResult!!
        assertEquals(3840, r.resolutionWidth)
        assertEquals(2160, r.resolutionHeight)
        assertEquals(24, r.fps)
        assertEquals("h265", r.videoCodec)
        assertEquals(50_000_000L, r.videoBitrate)
        // 5 fields specified; all 5 actually changed (vs DEFAULT_1080P).
        assertEquals(
            setOf("resolution", "frameRate", "videoCodec", "videoBitrate"),
            r.updatedFields.toSet(),
            "all changed fields reported",
        )
    }

    // ── 5. updatedFields = ACTUAL change, not "specified" ───

    @Test fun specifiedSameValueDoesNotCountAsUpdated() = runTest {
        // Marquee no-op-edit pin: explicitly setting `videoCodec="h264"`
        // when current is already h264 must produce empty updatedFields.
        // Drift would lie about state to the agent.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        // Default profile has videoCodec = "h264", audioCodec = "aac",
        // container = "mp4".
        val result = executeSetOutputProfile(
            store,
            input(
                projectId = created.id.value,
                videoCodec = "h264", // matches current
                audioCodec = "aac", // matches current
                container = "mp4", // matches current
            ),
            ctx,
        )
        val r = result.data!!.setOutputProfileResult!!
        assertTrue(
            r.updatedFields.isEmpty(),
            "no-op edit must report empty updatedFields; got ${r.updatedFields}",
        )
        assertTrue(
            "unchanged (all provided values matched current)" in result.outputForLlm,
            "expected unchanged-summary phrasing; got: ${result.outputForLlm}",
        )
    }

    @Test fun mixedSpecifiedSameAndChangedReportsOnlyChanged() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val result = executeSetOutputProfile(
            store,
            input(
                projectId = created.id.value,
                videoCodec = "h264", // matches default → no-op
                fps = 60, // changes from 30 → updated
            ),
            ctx,
        )
        val r = result.data!!.setOutputProfileResult!!
        assertEquals(
            listOf("frameRate"),
            r.updatedFields,
            "only frameRate in updatedFields (videoCodec was specified-but-same)",
        )
    }

    // ── 6. Only OutputProfile mutates (timeline untouched) ──

    @Test fun timelineResolutionUntouchedOnOutputProfileEdit() = runTest {
        // Marquee separation pin: timeline.resolution and
        // timeline.frameRate are AUTHORING grid; outputProfile.resolution
        // is RENDER spec. Setting render spec must NOT reflow the
        // authoring timeline. Drift to "update both" would break a
        // workflow where the user edits at 1080p but exports at 4k.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val before = store.get(created.id)!!
        val timelineResolutionBefore = before.timeline.resolution
        val timelineFrameRateBefore = before.timeline.frameRate

        executeSetOutputProfile(
            store,
            input(
                projectId = created.id.value,
                resolutionWidth = 3840,
                resolutionHeight = 2160,
                fps = 60,
            ),
            ctx,
        )
        val after = store.get(created.id)!!
        assertEquals(
            timelineResolutionBefore,
            after.timeline.resolution,
            "timeline.resolution untouched (authoring grid distinct from render spec)",
        )
        assertEquals(
            timelineFrameRateBefore,
            after.timeline.frameRate,
            "timeline.frameRate untouched",
        )
        // outputProfile DID change.
        assertEquals(3840, after.outputProfile.resolution.width)
        assertEquals(2160, after.outputProfile.resolution.height)
        assertNotEquals(
            after.outputProfile.resolution,
            after.timeline.resolution,
            "outputProfile and timeline can diverge",
        )
    }

    // ── Shape pins ──────────────────────────────────────────

    @Test fun fpsRoundTripsAsFrameRateNumeratorOverDenominatorOne() = runTest {
        // Pin: input fps is Int; impl wraps as FrameRate(it, 1), and
        // output `fpsOut` is computed as `denominator==1 ? numerator :
        // numerator/denominator`. So a clean integer fps round-trips
        // exactly without surprises.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        for (target in listOf(24, 25, 30, 50, 60, 120)) {
            val result = executeSetOutputProfile(
                store,
                input(projectId = created.id.value, fps = target),
                ctx,
            )
            val r = result.data!!.setOutputProfileResult!!
            assertEquals(target, r.fps, "fps round-trip for $target")
        }
    }

    @Test fun titleFormatCitesProjectId() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val result = executeSetOutputProfile(
            store,
            input(projectId = created.id.value, fps = 60),
            ctx,
        )
        assertEquals("set output profile ${created.id.value}", result.title)
    }

    @Test fun summaryCitesUpdatedFieldsAndResolvedSpec() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val result = executeSetOutputProfile(
            store,
            input(
                projectId = created.id.value,
                resolutionWidth = 2560,
                resolutionHeight = 1440,
                fps = 60,
            ),
            ctx,
        )
        val msg = result.outputForLlm
        assertTrue("Updated " in msg, "expected 'Updated' prefix; got: $msg")
        assertTrue("resolution" in msg, "expected 'resolution' cited; got: $msg")
        assertTrue("frameRate" in msg, "expected 'frameRate' cited; got: $msg")
        assertTrue("2560x1440" in msg, "expected resolved spec; got: $msg")
        assertTrue("@60fps" in msg, "expected fps in spec; got: $msg")
    }

    @Test fun mutationActuallyPersists() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        executeSetOutputProfile(
            store,
            input(
                projectId = created.id.value,
                resolutionWidth = 1280,
                resolutionHeight = 720,
                videoBitrate = 4_000_000L,
            ),
            ctx,
        )
        val after = store.get(created.id)!!
        assertEquals(Resolution(1280, 720), after.outputProfile.resolution)
        assertEquals(4_000_000L, after.outputProfile.videoBitrate)
    }

    @Test fun multiCallAccumulatesPartialEdits() = runTest {
        // Pin: each call reads `current.outputProfile` AT-CALL-TIME, so
        // a sequence of partial edits accumulates rather than each
        // resetting unspecified fields. Drift to "current = DEFAULT"
        // would silently wipe field N+1 on every edit N.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        // Call 1: change resolution.
        executeSetOutputProfile(
            store,
            input(
                projectId = created.id.value,
                resolutionWidth = 2560,
                resolutionHeight = 1440,
            ),
            ctx,
        )
        // Call 2: change videoCodec only.
        executeSetOutputProfile(
            store,
            input(projectId = created.id.value, videoCodec = "h265"),
            ctx,
        )
        val after = store.get(created.id)!!
        // Both edits accumulated.
        assertEquals(Resolution(2560, 1440), after.outputProfile.resolution, "call-1 resolution preserved")
        assertEquals("h265", after.outputProfile.videoCodec, "call-2 codec applied")
    }
}
