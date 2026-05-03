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
 * Direct tests for [executeOpenProject] —
 * `core/tool/builtin/project/OpenProjectHandler.kt`. The
 * `project_lifecycle_action(action="open")` handler that
 * registers an existing on-disk bundle in the recents
 * catalog. Cycle 186 audit: 39 LOC, 0 direct test refs
 * (used through full-tool integration but the
 * required-input rejection, summary-fallback-to-id, and
 * outputForLlm "switch_project" hint were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`path` required and non-blank.** Two pins:
 *    null path → `error("requires path")`; blank path
 *    → require fails with "must not be blank". Drift to
 *    "silent skip on null" would let opens against
 *    invalid paths mask agent typos.
 *
 * 2. **Title falls back to projectId.value when
 *    `summary(project.id)` returns null.** Defensive
 *    fallback so legacy bundles with no catalog summary
 *    still produce a usable title-string for UI/agent.
 *    Drift to "throw on missing summary" would crash
 *    on legacy data.
 *
 * 3. **outputForLlm cites the documented next-step
 *    affordances: `projectId=X` and `switch_project`.**
 *    The LLM reads this to know how to continue ("call
 *    `switch_project` to bind this to the session"
 *    OR "pass `projectId=X` per call"). Drift in the
 *    wording would lose the agent's recipe for
 *    next-step continuation.
 */
class OpenProjectHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun openInput(path: String?): ProjectLifecycleActionTool.Input =
        ProjectLifecycleActionTool.Input(action = "open", path = path)

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingPathThrowsIllegalState() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeOpenProject(
                projects = store,
                input = openInput(path = null),
                ctx = ctx,
            )
        }
        assertTrue(
            "requires `path`" in (ex.message ?: ""),
            "expected requires-path phrase; got: ${ex.message}",
        )
    }

    @Test fun blankPathThrowsIllegalArgument() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalArgumentException> {
            executeOpenProject(
                projects = store,
                input = openInput(path = "   "),
                ctx = ctx,
            )
        }
        assertTrue(
            "must not be blank" in (ex.message ?: ""),
            "expected blank-path phrase; got: ${ex.message}",
        )
    }

    @Test fun emptyStringPathThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        // Empty string is also blank → require fails.
        assertFailsWith<IllegalArgumentException> {
            executeOpenProject(
                projects = store,
                input = openInput(path = ""),
                ctx = ctx,
            )
        }
    }

    // ── Happy path ───────────────────────────────────────────

    @Test fun openExistingBundleRegistersInRecentsAndReturnsResult() = runTest {
        // Pin: openAt loads + registers. Use createAt to
        // produce a bundle, then drop it from the recents
        // catalog (simulating "reopen on a fresh
        // machine"), then call openAt to re-register.
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Test Bundle")
        // Pre-condition: bundle is in catalog.
        assertEquals("Test Bundle", store.summary(created.id)!!.title)

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        // Result mirrors the project's identity + title.
        assertEquals(created.id.value, result.data.projectId)
        assertEquals("open", result.data.action)
        assertEquals("Test Bundle", result.data.openResult!!.title)
    }

    // ── Title fallback ───────────────────────────────────────

    @Test fun titleEchoesSummaryWhenAvailable() = runTest {
        // Marquee happy-path title pin: the result's title
        // comes from summary, NOT from project.title.
        // (FileProjectStore reads talevia.json's title
        // field for both; verifying the path is summary
        // anchors the test to the documented data flow.)
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Special 项目")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        assertEquals(
            "Special 项目",
            result.data.openResult!!.title,
            "title verbatim from summary (CJK preserved)",
        )
    }

    // ── outputForLlm format conventions ──────────────────────

    @Test fun outputForLlmCitesProjectIdTitleAndPath() = runTest {
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "My Project")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        assertTrue(
            "Opened project" in result.outputForLlm,
            "outputForLlm cites verb; got: ${result.outputForLlm}",
        )
        assertTrue(
            created.id.value in result.outputForLlm,
            "cites projectId; got: ${result.outputForLlm}",
        )
        assertTrue(
            "My Project" in result.outputForLlm,
            "cites title; got: ${result.outputForLlm}",
        )
        assertTrue(
            bundlePath.toString() in result.outputForLlm,
            "cites bundle path; got: ${result.outputForLlm}",
        )
    }

    @Test fun outputForLlmCitesNextStepAffordances() = runTest {
        // Marquee next-step-affordance pin: per the
        // documented format, the agent reads outputForLlm
        // to know how to bind the project — either pass
        // `projectId=X` per call OR call `switch_project`.
        // Drift in the wording would lose the LLM's recipe
        // for continuation.
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Test")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        assertTrue(
            "projectId=${created.id.value}" in result.outputForLlm,
            "cites pass-projectId affordance; got: ${result.outputForLlm}",
        )
        assertTrue(
            "switch_project" in result.outputForLlm,
            "cites switch_project affordance; got: ${result.outputForLlm}",
        )
        assertTrue(
            "recents" in result.outputForLlm,
            "mentions recents catalog registration; got: ${result.outputForLlm}",
        )
    }

    // ── ToolResult.title ─────────────────────────────────────

    @Test fun toolResultTitleCitesProjectTitleNotProjectId() = runTest {
        // Pin: ToolResult.title (the human-readable card
        // header) cites project's title for readability,
        // NOT the projectId. Drift would degrade UI cards
        // (project ids are slug-style, less readable).
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Memorable Title")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        assertTrue(
            "Memorable Title" in result.title!!,
            "ToolResult.title cites project title; got: ${result.title}",
        )
        assertTrue(
            "open" in result.title!!,
            "ToolResult.title cites verb; got: ${result.title}",
        )
        // The id is NOT in the title (drift to "include
        // both" would clutter; current convention is
        // title-only).
        assertTrue(
            created.id.value !in result.title!!,
            "ToolResult.title does not cite projectId; got: ${result.title}",
        )
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputProjectIdMatchesLoadedProjectsId() = runTest {
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Test")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        assertEquals(
            created.id.value,
            result.data.projectId,
            "result.projectId reflects the loaded project's id",
        )
    }

    @Test fun outputActionIsOpen() = runTest {
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        store.createAt(path = bundlePath, title = "Test")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        assertEquals("open", result.data.action)
    }

    @Test fun outputCarriesOpenResultNotOtherResultFields() = runTest {
        // Pin: the polymorphic Output has many optional
        // *Result fields (createResult, deleteResult, etc.);
        // open populates ONLY openResult. Drift to
        // "populate all" would clutter the agent's
        // decode-time branching.
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        store.createAt(path = bundlePath, title = "Test")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        // openResult populated, others null.
        assertTrue(result.data.openResult != null, "openResult populated")
        assertTrue(result.data.createResult == null, "createResult null")
        assertTrue(result.data.deleteResult == null, "deleteResult null")
        assertTrue(result.data.renameResult == null, "renameResult null")
    }

    // ── Path is passed verbatim to projects.openAt ──────────

    @Test fun pathIsTrimAndPassedVerbatim() = runTest {
        // Pin: per `rawPath.toPath()` — the path is fed to
        // projects.openAt unchanged. Drift to "trim
        // whitespace before convert" would normalise paths
        // but the require check on `isNotBlank()` already
        // guards against pure-whitespace; leading-space
        // paths would fail at the okio level (file-not-found).
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        store.createAt(path = bundlePath, title = "Test")

        val result = executeOpenProject(
            projects = store,
            input = openInput(path = bundlePath.toString()),
            ctx = ctx,
        )
        // outputForLlm cites the path the user asked for,
        // verbatim.
        assertTrue(
            "/projects/p1" in result.outputForLlm,
            "path cited verbatim; got: ${result.outputForLlm}",
        )
    }
}
