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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [executeDeleteProject] —
 * `core/tool/builtin/project/DeleteProjectHandler.kt`. The
 * `project_lifecycle_action(action="delete")` handler.
 * Cycle 185 audit: 48 LOC, 0 direct test refs (used through
 * full-tool integration but the required-input rejection,
 * deleteFiles-default-false safe path, on-disk-bundle
 * removal vs catalog-only, and outputForLlm orphaned-
 * session warning were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`deleteFiles=false` (default) keeps the on-disk
 *    bundle.** Per kdoc: "default keeps the bundle on
 *    disk so an accidental delete never destroys
 *    user-authored files." Drift to "always delete files"
 *    would silently destroy user work on every delete
 *    where the agent forgot the flag.
 *
 * 2. **`deleteFiles=true` removes both catalog AND on-disk
 *    bundle.** The full-cleanup path. Pin asserts the
 *    bundle directory no longer exists after delete.
 *
 * 3. **outputForLlm always cites "Sessions that reference
 *    this project are now orphaned."** This is the
 *    documented user-facing warning for the cascade
 *    behaviour. Drift in the wording would lose the
 *    "what just happened to my sessions?" diagnostic.
 */
class DeleteProjectHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun deleteInput(
        projectId: String?,
        deleteFiles: Boolean = false,
    ): ProjectLifecycleActionTool.Input = ProjectLifecycleActionTool.Input(
        action = "delete",
        projectId = projectId,
        deleteFiles = deleteFiles,
    )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingProjectIdThrowsIllegalState() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeDeleteProject(
                projects = store,
                input = deleteInput(projectId = null),
                ctx = ctx,
            )
        }
        assertTrue(
            "requires `projectId`" in (ex.message ?: ""),
            "expected requires-projectId phrase; got: ${ex.message}",
        )
    }

    @Test fun missingProjectThrowsWithIdInMessage() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeDeleteProject(
                projects = store,
                input = deleteInput(projectId = "ghost-id"),
                ctx = ctx,
            )
        }
        assertTrue(
            "not found" in (ex.message ?: ""),
            "expected not-found phrase; got: ${ex.message}",
        )
        assertTrue(
            "ghost-id" in (ex.message ?: ""),
            "expected id in message; got: ${ex.message}",
        )
    }

    // ── Default deleteFiles=false: bundle preserved ──────────

    @Test fun deleteFilesFalseRemovesCatalogButKeepsBundleOnDisk() = runTest {
        // Marquee safe-default pin: per kdoc "default keeps
        // the bundle on disk so an accidental delete never
        // destroys user-authored files." Drift to "always
        // delete" would lose user work.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Original")

        // Sanity: bundle exists pre-delete.
        assertTrue(fs.exists(bundlePath), "bundle exists before delete")

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value, deleteFiles = false),
            ctx = ctx,
        )

        // Catalog: project no longer registered.
        assertNull(store.summary(created.id), "catalog removed")
        // On-disk: bundle directory STILL exists.
        assertTrue(
            fs.exists(bundlePath),
            "bundle preserved on disk (default safe behavior)",
        )
        // Result reflects bundle preservation.
        val deleteResult = result.data.deleteResult!!
        assertEquals(false, deleteResult.filesDeleted, "filesDeleted=false default")
        assertEquals("Original", deleteResult.title)
    }

    @Test fun deleteFilesFalseOutputForLlmDoesNotMentionFilesRemoved() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Test")

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value, deleteFiles = false),
            ctx = ctx,
        )
        // Pin: "on-disk bundle ... removed" suffix is
        // ABSENT when deleteFiles=false. Drift would
        // confuse the agent into thinking files were
        // deleted.
        assertTrue(
            "on-disk bundle" !in result.outputForLlm,
            "no files-deleted suffix; got: ${result.outputForLlm}",
        )
    }

    // ── deleteFiles=true: bundle removed ─────────────────────

    @Test fun deleteFilesTrueRemovesBundleOnDiskAlongsideCatalog() = runTest {
        // Marquee full-cleanup pin: deleteFiles=true wipes
        // both catalog AND on-disk bundle.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Original")

        assertTrue(fs.exists(bundlePath), "sanity: bundle exists pre-delete")

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value, deleteFiles = true),
            ctx = ctx,
        )

        // Catalog cleared.
        assertNull(store.summary(created.id))
        // On-disk: bundle directory removed.
        assertTrue(
            !fs.exists(bundlePath),
            "bundle directory removed from disk",
        )
        // Result reflects bundle removal.
        val deleteResult = result.data.deleteResult!!
        assertEquals(
            true,
            deleteResult.filesDeleted,
            "filesDeleted=true when bundle was removed",
        )
        assertNotNull(
            deleteResult.path,
            "path field populated when bundle had a path",
        )
    }

    @Test fun deleteFilesTrueOutputForLlmCitesBundleRemoved() = runTest {
        val store = ProjectStoreTestKit.create()
        val bundlePath = "/projects/p1".toPath()
        val created = store.createAt(path = bundlePath, title = "Test")

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value, deleteFiles = true),
            ctx = ctx,
        )
        // Pin: outputForLlm cites the on-disk path that
        // was removed. Agent reads this to confirm what
        // happened.
        assertTrue(
            "on-disk bundle" in result.outputForLlm,
            "cites on-disk bundle removal; got: ${result.outputForLlm}",
        )
        assertTrue(
            bundlePath.toString() in result.outputForLlm,
            "cites bundle path; got: ${result.outputForLlm}",
        )
        assertTrue(
            "removed" in result.outputForLlm,
            "cites removal verb; got: ${result.outputForLlm}",
        )
    }

    // ── Orphaned-session warning ─────────────────────────────

    @Test fun outputForLlmAlwaysCitesSessionsOrphanedWarning() = runTest {
        // Marquee warning-message pin: per the documented
        // cascade behaviour, sessions referencing the
        // deleted project become orphaned. The warning
        // must surface in EVERY delete (regardless of
        // deleteFiles flag).
        val store = ProjectStoreTestKit.create()
        val createdA = store.createAt(path = "/projects/a".toPath(), title = "A")
        val createdB = store.createAt(path = "/projects/b".toPath(), title = "B")

        // Both deleteFiles=false AND deleteFiles=true
        // produce the warning.
        val resultKeep = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = createdA.id.value, deleteFiles = false),
            ctx = ctx,
        )
        val resultDelete = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = createdB.id.value, deleteFiles = true),
            ctx = ctx,
        )

        for (result in listOf(resultKeep, resultDelete)) {
            assertTrue(
                "Sessions that reference this project are now orphaned" in result.outputForLlm,
                "every delete cites orphaned warning; got: ${result.outputForLlm}",
            )
        }
    }

    // ── DeleteResult shape ───────────────────────────────────

    @Test fun deleteResultEchoesProjectTitle() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Special Title 项目",
        )

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value),
            ctx = ctx,
        )
        // Title echoed verbatim — drift to "echo id as
        // title" or trim/normalize would mislead UI.
        assertEquals("Special Title 项目", result.data.deleteResult!!.title)
    }

    @Test fun deleteResultFilesDeletedIsFalseWhenDeleteFilesFalse() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Test")

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value, deleteFiles = false),
            ctx = ctx,
        )
        // Pin: filesDeleted = (deleteFiles && onDiskPath
        // != null). When deleteFiles=false, this is false
        // even though onDiskPath is non-null.
        assertEquals(false, result.data.deleteResult!!.filesDeleted)
    }

    @Test fun deleteResultPathIsAlwaysPopulatedWhenBundleHasOnDiskPath() = runTest {
        // Pin: the path field is populated regardless of
        // deleteFiles flag — useful for the agent to know
        // "where the bundle USED to live" even when the
        // catalog-only delete left the directory in place.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Test")

        val resultKeep = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value, deleteFiles = false),
            ctx = ctx,
        )
        // Path captured even though files weren't deleted.
        assertNotNull(
            resultKeep.data.deleteResult!!.path,
            "path captured for bundle-preserving delete",
        )
        assertTrue(
            "/projects/p1" in resultKeep.data.deleteResult!!.path!!,
            "path matches the bundle's location",
        )
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputProjectIdEchoesInputProjectId() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Test")

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value),
            ctx = ctx,
        )
        assertEquals(created.id.value, result.data.projectId)
        assertEquals("delete", result.data.action)
    }

    @Test fun toolResultTitleCitesProjectTitle() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Memorable Project",
        )

        val result = executeDeleteProject(
            projects = store,
            input = deleteInput(projectId = created.id.value),
            ctx = ctx,
        )
        assertTrue(
            "Memorable Project" in result.title!!,
            "ToolResult.title cites project title; got: ${result.title}",
        )
        assertTrue(
            "delete" in result.title!!,
            "ToolResult.title cites verb; got: ${result.title}",
        )
    }
}
