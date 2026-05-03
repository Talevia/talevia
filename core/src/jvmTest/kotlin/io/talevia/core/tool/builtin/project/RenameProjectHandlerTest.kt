package io.talevia.core.tool.builtin.project

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [executeRenameProject] —
 * `core/tool/builtin/project/RenameProjectHandler.kt`. The
 * `project_lifecycle_action(action="rename")` handler.
 * Cycle 179 audit: 59 LOC, 0 direct test refs (used through
 * full-tool integration but the branch-level contracts —
 * required-input rejection, blank-title require, missing-
 * project error, no-op same-title path, happy path with
 * previousTitle/title diff in result — were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Required inputs: `projectId` + `title` non-null,
 *    `title.isNotBlank()`.** Three pins — null projectId,
 *    null title, blank title — each throws with the
 *    documented error / require message. Drift to "silent
 *    no-op on null" would let renames pass without taking
 *    effect; drift to "accept blank title" would set the
 *    project's title to whitespace.
 *
 * 2. **Same-title path: NO-OP, returns "no change" message
 *    AND does NOT call setTitle.** Per kdoc: "a no-op if
 *    the title is already what's asked for so the agent
 *    doesn't have to filter 'is-this-a-real-change'
 *    itself." Drift to "always setTitle" would bump
 *    Project.updatedAtEpochMs (unnecessary) AND emit unnecessary
 *    bus events.
 *
 * 3. **Different-title path: setTitle invoked, result
 *    carries `previousTitle = old, title = new`,
 *    outputForLlm cites both.** Drift in the
 *    previousTitle field would silently mislead UI / agent
 *    into thinking the rename was a no-op or affected the
 *    wrong project.
 */
class RenameProjectHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { /* no-op */ },
        messages = emptyList(),
    )

    private fun renameInput(
        projectId: String?,
        title: String?,
    ): ProjectLifecycleActionTool.Input = ProjectLifecycleActionTool.Input(
        action = "rename",
        projectId = projectId,
        title = title,
    )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingProjectIdThrowsIllegalState() = runTest {
        // Pin: projectId is required. The kdoc-adjacent
        // `error("...")` produces an IllegalStateException
        // with the documented "requires projectId" message.
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeRenameProject(
                projects = store,
                input = renameInput(projectId = null, title = "anything"),
                ctx = ctx,
            )
        }
        assertTrue(
            "requires `projectId`" in (ex.message ?: ""),
            "expected 'requires projectId' message; got: ${ex.message}",
        )
    }

    @Test fun missingTitleThrowsIllegalState() = runTest {
        // Pin: title is required (not just optional).
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeRenameProject(
                projects = store,
                input = renameInput(projectId = "any", title = null),
                ctx = ctx,
            )
        }
        assertTrue(
            "requires `title`" in (ex.message ?: ""),
            "expected 'requires title' message; got: ${ex.message}",
        )
    }

    @Test fun blankTitleThrowsIllegalArgument() = runTest {
        // Marquee blank-rejection pin: drift to "accept blank
        // title" would set the project's title to whitespace
        // (unrenderable in UI). The kdoc-adjacent
        // `require(newTitle.isNotBlank())` throws
        // IllegalArgumentException with "title must not be
        // blank".
        val store = ProjectStoreTestKit.create()
        val createdProject = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Original",
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            executeRenameProject(
                projects = store,
                input = renameInput(
                    projectId = createdProject.id.value,
                    title = "   ",
                ),
                ctx = ctx,
            )
        }
        assertTrue(
            "must not be blank" in (ex.message ?: ""),
            "expected blank-title phrase; got: ${ex.message}",
        )
    }

    @Test fun emptyStringTitleAlsoRejected() = runTest {
        // Pin: empty string is also blank.
        val store = ProjectStoreTestKit.create()
        val createdProject = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Original",
        )
        assertFailsWith<IllegalArgumentException> {
            executeRenameProject(
                projects = store,
                input = renameInput(
                    projectId = createdProject.id.value,
                    title = "",
                ),
                ctx = ctx,
            )
        }
    }

    // ── Missing project rejection ────────────────────────────

    @Test fun missingProjectThrowsIllegalState() = runTest {
        // Pin: project not in store → error("not found").
        // Drift to "silently create" would let renames
        // succeed against ghost projects.
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeRenameProject(
                projects = store,
                input = renameInput(projectId = "ghost", title = "Anything"),
                ctx = ctx,
            )
        }
        assertTrue(
            "not found" in (ex.message ?: ""),
            "expected not-found phrase; got: ${ex.message}",
        )
        assertTrue(
            "ghost" in (ex.message ?: ""),
            "expected ghost id in message; got: ${ex.message}",
        )
    }

    // ── Same-title no-op ─────────────────────────────────────

    @Test fun renameToSameTitleIsNoOp() = runTest {
        // The marquee no-op pin: per kdoc "a no-op if the
        // title is already what's asked for so the agent
        // doesn't have to filter 'is-this-a-real-change'
        // itself." The result has previousTitle == title
        // (both equal to the existing title); outputForLlm
        // cites the no-change phrasing.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Same Name",
        )
        val before = store.summary(created.id)!!
        val updatedAtBefore = before.updatedAtEpochMs

        val result = executeRenameProject(
            projects = store,
            input = renameInput(
                projectId = created.id.value,
                title = "Same Name",
            ),
            ctx = ctx,
        )

        // Result reflects no-op: previousTitle == title.
        val rename = result.data.renameResult!!
        assertEquals("Same Name", rename.previousTitle)
        assertEquals("Same Name", rename.title)
        assertEquals(
            "Same Name",
            rename.title,
            "rename.title equals current — drift to 'absent' would mislead UI",
        )
        // outputForLlm cites the no-change phrasing (the
        // agent reads this for "did anything happen?")
        assertTrue(
            "no change" in result.outputForLlm,
            "outputForLlm cites no-change; got: ${result.outputForLlm}",
        )

        // setTitle was NOT called — store's updatedAt is
        // unchanged. Drift to "always setTitle" would bump
        // updatedAt unnecessarily.
        val after = store.summary(created.id)!!
        assertEquals(
            updatedAtBefore,
            after.updatedAtEpochMs,
            "store.updatedAtEpochMs unchanged on no-op rename",
        )
        assertEquals(
            "Same Name",
            after.title,
            "store title unchanged on no-op rename",
        )
    }

    // ── Different-title happy path ───────────────────────────

    @Test fun renameToDifferentTitleAppliesAndReportsBoth() = runTest {
        // Marquee happy-path pin: title differs → setTitle
        // invoked, result carries previousTitle = old AND
        // title = new (NOT one or the other).
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Original Title",
        )

        val result = executeRenameProject(
            projects = store,
            input = renameInput(
                projectId = created.id.value,
                title = "New Title",
            ),
            ctx = ctx,
        )

        // Result has both fields populated correctly.
        val rename = result.data.renameResult!!
        assertEquals(
            "Original Title",
            rename.previousTitle,
            "previousTitle captured BEFORE the mutation",
        )
        assertEquals(
            "New Title",
            rename.title,
            "title is the new value",
        )
        // outputForLlm cites BOTH old and new — the agent
        // reads this to confirm what happened.
        assertTrue(
            "Original Title" in result.outputForLlm,
            "outputForLlm cites old title; got: ${result.outputForLlm}",
        )
        assertTrue(
            "New Title" in result.outputForLlm,
            "outputForLlm cites new title; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Renamed" in result.outputForLlm,
            "outputForLlm cites action; got: ${result.outputForLlm}",
        )

        // Store reflects the new title.
        val after = store.summary(created.id)!!
        assertEquals(
            "New Title",
            after.title,
            "store.title updated to new value",
        )
    }

    @Test fun renameProjectIdEchoesIdInResult() = runTest {
        // Pin: result.projectId echoes the input's
        // projectId. Drift to "echo title as projectId"
        // (paste error) would silently mislead.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Original",
        )

        val result = executeRenameProject(
            projects = store,
            input = renameInput(
                projectId = created.id.value,
                title = "Renamed",
            ),
            ctx = ctx,
        )
        assertEquals(created.id.value, result.data.projectId)
        assertEquals("rename", result.data.action)
    }

    // ── Title preservation across the pipeline ───────────────

    @Test fun unicodeAndPunctuationInTitleSurvivesEndToEnd() {
        runTest {
            // Pin: title is preserved verbatim — no trim,
            // no normalize, no escape.
            val store = ProjectStoreTestKit.create()
            val created = store.createAt(
                path = "/projects/p1".toPath(),
                title = "Old",
            )
            val newTitle = "  我的项目: 「2026 春」  "
            val result = executeRenameProject(
                projects = store,
                input = renameInput(
                    projectId = created.id.value,
                    title = newTitle,
                ),
                ctx = ctx,
            )
            assertEquals(newTitle, result.data.renameResult!!.title)
            assertEquals(
                newTitle,
                store.summary(created.id)!!.title,
                "store stored title verbatim — no normalisation",
            )
        }
    }

    // ── Title field consistency in result.title vs ToolResult.title ──

    @Test fun toolResultTitleCitesProjectId() = runTest {
        // Pin: ToolResult.title (the human-readable header
        // for the tool call card) cites the projectId, NOT
        // the project's title. This decouples the card
        // header from the title-rename semantics.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Old",
        )

        val result = executeRenameProject(
            projects = store,
            input = renameInput(projectId = created.id.value, title = "New"),
            ctx = ctx,
        )
        assertTrue(
            created.id.value in result.title!!,
            "ToolResult.title cites projectId; got: ${result.title}",
        )
        assertTrue(
            "rename" in result.title!!,
            "ToolResult.title cites verb; got: ${result.title}",
        )
    }
}
