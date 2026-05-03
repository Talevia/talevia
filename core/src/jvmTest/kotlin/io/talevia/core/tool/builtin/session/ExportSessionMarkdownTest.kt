package io.talevia.core.tool.builtin.session

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.PlanApprovalStatus
import io.talevia.core.session.PlanStep
import io.talevia.core.session.PlanStepStatus
import io.talevia.core.session.Session
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TodoStatus
import io.talevia.core.session.ToolState
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [formatSessionAsMarkdown] — the human-readable
 * transcript renderer for `session_action(action="export",
 * format="markdown")`. Cycle 104 audit: 264 LOC, **zero** transitive
 * test references; the renderer is reachable only via the export
 * tool, where its output is treated as opaque text by callers
 * (operator pastes it into a doc / bug report).
 *
 * This is a "user-paste-this-into-a-bug-report" surface — every
 * rendering choice the renderer makes (which Part types fold to
 * blockquote, which get dropped, how state strings format) is part
 * of an unwritten contract with downstream readers (humans + GitHub
 * markdown renderer). A regression dropping the wrong Part type
 * silently corrupts every shared transcript without any compile-
 * time signal.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Format version embedded in header.** The kdoc commits to
 *    "future breaking changes ... bump the version without
 *    quietly drifting away from old archives." Test pins
 *    `talevia-session-export-md-v1` literal so a refactor that
 *    bumps the format MUST update this test, forcing explicit
 *    cross-archive thinking.
 *
 * 2. **High-frequency low-signal Parts dropped.** RenderProgress,
 *    StepStart, StepFinish are intentionally suppressed (kdoc
 *    rationale: "high-frequency low-signal in a transcript
 *    reader's eye"). A regression including them would clutter
 *    every export with hundreds of progress noise lines.
 *
 * 3. **Tool-state-discriminated rendering.** Each ToolState
 *    (Pending / Running / Completed / Failed / Cancelled)
 *    produces a different visible shape. Cancelled vs Failed in
 *    particular MUST render distinctly so a reader can tell
 *    "the user pressed Ctrl-C" from "the tool crashed" without
 *    parsing the message string.
 */
class ExportSessionMarkdownTest {

    private val now: Instant = Instant.fromEpochSeconds(1_714_500_000L)

    private fun newSession(
        title: String = "Test Session",
        archived: Boolean = false,
        parentId: SessionId? = null,
    ) = Session(
        id = SessionId("s-1"),
        projectId = ProjectId("proj-x"),
        title = title,
        parentId = parentId,
        createdAt = now,
        updatedAt = now,
        archived = archived,
    )

    private fun userMessage(id: String = "msg-user-1") = Message.User(
        id = MessageId(id),
        sessionId = SessionId("s-1"),
        createdAt = now,
        agent = "default",
        model = ModelRef("anthropic", "claude-opus-4-7"),
    )

    private fun asstMessage(id: String = "msg-asst-1", model: String = "claude-opus-4-7") = Message.Assistant(
        id = MessageId(id),
        sessionId = SessionId("s-1"),
        createdAt = now,
        parentId = MessageId("msg-user-1"),
        model = ModelRef("anthropic", model),
        finish = FinishReason.END_TURN,
    )

    private fun textPart(messageId: String, text: String, partId: String = "p-text") = Part.Text(
        id = PartId(partId),
        messageId = MessageId(messageId),
        sessionId = SessionId("s-1"),
        createdAt = now,
        text = text,
    )

    // ── header section ─────────────────────────────────────────────

    @Test fun headerCarriesTitleIdProjectFormatAndCounts() {
        val session = newSession(title = "First Session")
        val md = formatSessionAsMarkdown(session, emptyList(), emptyList())
        assertTrue("# Session: First Session" in md, "title heading missing; got: $md")
        assertTrue("- **id**: `s-1`" in md, "id row missing; got: $md")
        assertTrue("- **project**: `proj-x`" in md, "project row missing; got: $md")
        assertTrue("- **format**: $SESSION_MARKDOWN_FORMAT_VERSION" in md, "format row missing; got: $md")
        assertTrue("- **messages**: 0" in md, "message count row missing")
        assertTrue("**parts**: 0" in md, "part count missing")
    }

    @Test fun formatVersionConstantIsTheV1Literal() {
        // Pin: a refactor bumping the format string MUST update this
        // test, forcing the cross-archive compatibility decision.
        assertEquals("talevia-session-export-md-v1", SESSION_MARKDOWN_FORMAT_VERSION)
    }

    @Test fun archivedAndForkedFromSurfaceWhenSet() {
        val session = newSession(archived = true, parentId = SessionId("parent-sid"))
        val md = formatSessionAsMarkdown(session, emptyList(), emptyList())
        assertTrue("- **archived**: yes" in md, "archived row missing; got: $md")
        assertTrue("- **forked-from**: `parent-sid`" in md, "forked-from row missing; got: $md")
    }

    @Test fun archivedAndForkedFromOmittedWhenUnset() {
        val md = formatSessionAsMarkdown(newSession(), emptyList(), emptyList())
        assertFalse("archived" in md, "archived must NOT appear when false; got: $md")
        assertFalse("forked-from" in md, "forked-from must NOT appear when null; got: $md")
    }

    @Test fun emptySessionRendersExplicitMarker() {
        val md = formatSessionAsMarkdown(newSession(), emptyList(), emptyList())
        assertTrue(
            "_(empty session — no messages yet)_" in md,
            "empty marker missing; got: $md",
        )
    }

    // ── message headers ────────────────────────────────────────────

    @Test fun userMessageHeaderShowsRoleAndShortIdSuffix() {
        // Pin: "## user · <createdAt> · `<last8 of id>`". The
        // truncated id keeps headers compact when ids are
        // long uuids.
        val u = userMessage(id = "user-message-id-abcd1234")
        val md = formatSessionAsMarkdown(newSession(), listOf(u), emptyList())
        assertTrue("## user" in md, "user header missing; got: $md")
        assertTrue("`abcd1234`" in md, "8-char id tail missing; got: $md")
    }

    @Test fun assistantMessageHeaderIncludesModel() {
        val a = asstMessage(model = "claude-opus-4-7")
        val md = formatSessionAsMarkdown(newSession(), listOf(a), emptyList())
        assertTrue("## assistant (claude-opus-4-7)" in md, "model tag missing; got: $md")
    }

    @Test fun assistantHeaderOmitsModelSuffixWhenBlank() {
        val a = asstMessage(model = "")
        val md = formatSessionAsMarkdown(newSession(), listOf(a), emptyList())
        // Pin: the parens around model id MUST NOT appear empty.
        assertFalse("()" in md, "no empty parens when model id blank; got: $md")
        assertTrue("## assistant ·" in md, "assistant header still present; got: $md")
    }

    // ── per-part rendering ────────────────────────────────────────

    @Test fun textPartRendersTrimmedBody() {
        val u = userMessage()
        val text = textPart("msg-user-1", "  hello world  \n\n")
        val md = formatSessionAsMarkdown(newSession(), listOf(u), listOf(text))
        // trimEnd preserves leading whitespace by design (kdoc body
        // is content); trailing whitespace removed.
        assertTrue("  hello world" in md, "leading whitespace preserved; got: $md")
        assertFalse("hello world  " in md, "trailing whitespace stripped")
    }

    @Test fun blankTextPartIsDropped() {
        // Pin: blank text parts return null → renderer skips
        // entirely. Otherwise empty text spans would create gaps.
        val u = userMessage()
        val blank = textPart("msg-user-1", "   \n\n")
        val nonBlank = textPart("msg-user-1", "real content", partId = "p-real")
        val md = formatSessionAsMarkdown(newSession(), listOf(u), listOf(blank, nonBlank))
        // Non-blank rendered, blank skipped (no empty paragraph
        // between header and content).
        assertTrue("real content" in md)
    }

    @Test fun reasoningPartRendersAsReasoningCallout() {
        val a = asstMessage()
        val r = Part.Reasoning(
            id = PartId("p-r"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            text = "step by step thinking\nmulti-line",
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(r))
        assertTrue("> [!REASONING]" in md, "REASONING callout marker missing; got: $md")
        assertTrue("> step by step thinking" in md)
        assertTrue("> multi-line" in md, "multi-line body must blockquote each line")
    }

    @Test fun compactionPartRendersAsCompactionCallout() {
        val a = asstMessage()
        val c = Part.Compaction(
            id = PartId("p-c"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            replacedFromMessageId = MessageId("m-from"),
            replacedToMessageId = MessageId("m-to"),
            summary = "summary of dropped messages",
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(c))
        assertTrue("> [!COMPACTION]" in md, "COMPACTION callout missing; got: $md")
        assertTrue("> summary of dropped messages" in md)
    }

    @Test fun mediaPartRendersOneLineMarkerWithAssetId() {
        val a = asstMessage()
        val m = Part.Media(
            id = PartId("p-m"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            assetId = AssetId("asset-123"),
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(m))
        assertTrue(
            "_[media: `asset-123`]_" in md,
            "media marker missing; got: $md",
        )
    }

    @Test fun renderProgressStepStartStepFinishAreDropped() {
        // The most-load-bearing pin in this file. Per kdoc:
        // "Render-progress / step-start / step-finish are
        // intentionally **dropped**". A regression keeping them
        // would clutter every export with hundreds of low-signal
        // lines, making transcripts unreadable.
        val a = asstMessage()
        val rp = Part.RenderProgress(
            id = PartId("p-rp"), messageId = a.id, sessionId = a.sessionId,
            createdAt = now, jobId = "job-1", ratio = 0.5f,
        )
        val ss = Part.StepStart(
            id = PartId("p-ss"), messageId = a.id, sessionId = a.sessionId, createdAt = now,
        )
        val sf = Part.StepFinish(
            id = PartId("p-sf"), messageId = a.id, sessionId = a.sessionId, createdAt = now,
            tokens = io.talevia.core.session.TokenUsage(),
            finish = FinishReason.END_TURN,
        )
        val real = textPart(a.id.value, "I did a thing")
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(rp, ss, sf, real))
        assertFalse("job-1" in md, "RenderProgress.jobId must NOT leak; got: $md")
        assertFalse("StepStart" in md, "StepStart marker must NOT appear")
        assertFalse("StepFinish" in md, "StepFinish marker must NOT appear")
        assertTrue("I did a thing" in md, "real text must still appear")
    }

    @Test fun toolPartCompletedRendersInputAndOutput() {
        val a = asstMessage()
        val tool = Part.Tool(
            id = PartId("p-tool"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            callId = CallId("call-id-9be4ef"),
            toolId = "generate_image",
            state = ToolState.Completed(
                input = buildJsonObject { put("prompt", "a dog") },
                outputForLlm = "asset-id=img-42",
                data = JsonPrimitive("img-42"),
            ),
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(tool))
        assertTrue("> [!TOOL] `generate_image`" in md, "TOOL callout with toolId missing; got: $md")
        assertTrue("`9be4ef`" in md, "call-id last-6 missing; got: $md")
        assertTrue("· completed" in md, "state suffix missing; got: $md")
        assertTrue("> **input**" in md)
        assertTrue("> ```json" in md, "input JSON code-fence missing; got: $md")
        assertTrue("a dog" in md, "input JSON content missing")
        assertTrue("> **output**" in md)
        assertTrue("> asset-id=img-42" in md, "output blockquoted; got: $md")
    }

    @Test fun toolPartFailedRendersFailedMessageNotOutput() {
        val a = asstMessage()
        val tool = Part.Tool(
            id = PartId("p-tool"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            callId = CallId("call-fail-x"),
            toolId = "generate_image",
            state = ToolState.Failed(
                input = buildJsonObject { put("prompt", "x") },
                message = "rate limited",
            ),
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(tool))
        assertTrue("· failed" in md, "state suffix 'failed' missing; got: $md")
        assertTrue("> **failed**: rate limited" in md, "failure message line missing; got: $md")
        // Failed must NOT render an output block.
        assertFalse("> **output**" in md, "Failed must not render output; got: $md")
    }

    @Test fun toolPartCancelledRendersCancelledMessage() {
        // Pin: Cancelled ≠ Failed. The kdoc + cycle-62 split commit
        // both insist on this distinction so post-mortem queries can
        // tell "user pressed Ctrl-C" from "tool crashed". Folding
        // them in markdown would silently lose that signal.
        val a = asstMessage()
        val tool = Part.Tool(
            id = PartId("p-tool"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            callId = CallId("call-cxl"),
            toolId = "generate_image",
            state = ToolState.Cancelled(
                input = buildJsonObject { put("prompt", "x") },
                message = "user cancel",
            ),
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(tool))
        assertTrue("· cancelled" in md, "state suffix 'cancelled' missing; got: $md")
        assertTrue("> **cancelled**: user cancel" in md, "cancel message missing; got: $md")
        // Distinct from Failed — pin both ways.
        assertFalse("> **failed**" in md, "must NOT render as failed; got: $md")
    }

    @Test fun toolPartPendingHasNoInputBlock() {
        // Pending state has no input yet → renderer skips the
        // input block entirely. A regression rendering an empty
        // ```json``` block would produce a malformed code fence.
        val a = asstMessage()
        val tool = Part.Tool(
            id = PartId("p-tool"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            callId = CallId("call-p"),
            toolId = "generate_image",
            state = ToolState.Pending,
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(tool))
        assertTrue("· pending" in md, "pending suffix missing; got: $md")
        assertFalse("> ```json" in md, "Pending must not emit input code fence; got: $md")
        assertFalse("> **input**" in md, "Pending must not emit input header; got: $md")
    }

    @Test fun todosPartRendersCheckboxesPerStatus() {
        val a = asstMessage()
        val todos = Part.Todos(
            id = PartId("p-todos"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            todos = listOf(
                TodoInfo(content = "buy milk", status = TodoStatus.PENDING),
                TodoInfo(content = "feed cat", status = TodoStatus.IN_PROGRESS),
                TodoInfo(content = "wash car", status = TodoStatus.COMPLETED),
                TodoInfo(content = "pay bill", status = TodoStatus.CANCELLED),
            ),
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(todos))
        assertTrue("**Todos** (4 item(s))" in md, "header missing; got: $md")
        // Pin per-status checkbox: pending=`[ ]`, in_progress=`[~]`,
        // completed=`[x]`, cancelled=`[-]`.
        assertTrue("- [ ] buy milk" in md, "PENDING checkbox missing; got: $md")
        assertTrue("- [~] feed cat" in md, "IN_PROGRESS checkbox missing")
        assertTrue("- [x] wash car" in md, "COMPLETED checkbox missing")
        assertTrue("- [-] pay bill" in md, "CANCELLED checkbox missing")
    }

    @Test fun planPartRendersGoalAndNumberedSteps() {
        val a = asstMessage()
        val plan = Part.Plan(
            id = PartId("p-plan"),
            messageId = a.id,
            sessionId = a.sessionId,
            createdAt = now,
            goalDescription = "render an export",
            steps = listOf(
                PlanStep(
                    step = 1,
                    toolName = "generate_image",
                    inputSummary = "prompt=\"sunset\"",
                    status = PlanStepStatus.COMPLETED,
                ),
                PlanStep(
                    step = 2,
                    toolName = "add_clip",
                    inputSummary = "video clip",
                    status = PlanStepStatus.PENDING,
                ),
            ),
            approvalStatus = PlanApprovalStatus.PENDING_APPROVAL,
        )
        val md = formatSessionAsMarkdown(newSession(), listOf(a), listOf(plan))
        assertTrue("**Plan**: render an export" in md, "goal missing; got: $md")
        // Step numbering uses (i + 1) — 1-based.
        assertTrue("1. `generate_image` — prompt=\"sunset\"" in md, "step 1 missing; got: $md")
        assertTrue("2. `add_clip` — video clip" in md, "step 2 missing")
        assertTrue("completed" in md, "step status surfaces lowercased")
        assertTrue("pending" in md)
    }

    @Test fun titleEscapesBackslashBacktickAndNewline() {
        // Pin escapeMarkdownInline: backslash → \\, backtick → \`,
        // newline → space. These three would otherwise corrupt
        // inline rendering (newline breaks the # heading; backtick
        // opens an unclosed inline code span).
        val session = newSession(title = "Mei `the cat`\\nslash\nnewline")
        val md = formatSessionAsMarkdown(session, emptyList(), emptyList())
        // Backtick escaped.
        assertTrue("Mei \\`the cat\\`" in md, "backtick must be escaped; got: $md")
        // Backslash doubled.
        assertTrue("\\\\nslash" in md, "backslash must be doubled; got: $md")
        // Newline → space.
        assertFalse(
            "\nnewline" in md.substringBefore("\n\n"),
            "newline in title must be replaced with space; got: $md",
        )
    }

    @Test fun outputAlwaysEndsWithSingleTrailingNewline() {
        // Pin trimEnd + "\n" pattern at line 126: regardless of
        // last-rendered Part shape, output ends with exactly one
        // newline. UIs rendering this in a `<pre>` block expect
        // a stable suffix.
        val session = newSession()
        val md = formatSessionAsMarkdown(session, emptyList(), emptyList())
        assertTrue(md.endsWith("\n"), "must end with newline; got: ...${md.takeLast(20)}")
        assertFalse(md.endsWith("\n\n"), "must not end with double newline")
    }
}
