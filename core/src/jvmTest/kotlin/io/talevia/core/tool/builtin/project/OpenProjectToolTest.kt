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

class OpenProjectToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun opensExistingBundleAndReturnsIdAndTitle() = runTest {
        // Bootstrap a bundle on a fake filesystem, then drop the in-memory
        // recents registry by spinning up a *fresh* store over the same fs —
        // simulates "user copied/cloned the bundle to this machine, no
        // registry entry yet".
        val (writerStore, fs) = ProjectStoreTestKit.createWithFs()
        val path = "/projects/copied".toPath()
        val originalId = writerStore.createAt(path = path, title = "Copied Movie").id

        // Simulate fresh process: new store + new registry, same fs.
        val freshStore = io.talevia.core.domain.FileProjectStore(
            registry = io.talevia.core.domain.RecentsRegistry(
                "/.talevia/recents-fresh.json".toPath(),
                fs,
            ),
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
        )
        // Pre-condition: the fresh store doesn't know about this id yet.
        assertEquals(null, freshStore.get(originalId))

        val tool = OpenProjectTool(freshStore)
        val result = tool.execute(OpenProjectTool.Input(path = path.toString()), ctx())

        assertEquals(originalId.value, result.data.projectId)
        assertEquals("Copied Movie", result.data.title)
        // After open, the project is reachable by id.
        assertTrue(freshStore.get(originalId) != null)
    }

    @Test fun openMissingPathThrows() = runTest {
        val (store, _) = ProjectStoreTestKit.createWithFs()
        val tool = OpenProjectTool(store)
        assertFailsWith<Throwable> {
            tool.execute(OpenProjectTool.Input(path = "/projects/does-not-exist"), ctx())
        }
    }

    @Test fun openDirectoryWithoutTaleviaJsonThrows() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        // Create the directory but no talevia.json inside.
        val emptyDir = "/projects/empty".toPath()
        fs.createDirectories(emptyDir)
        val tool = OpenProjectTool(store)
        assertFailsWith<Throwable> {
            tool.execute(OpenProjectTool.Input(path = emptyDir.toString()), ctx())
        }
    }

    @Test fun blankPathFailsLoud() = runTest {
        val (store, _) = ProjectStoreTestKit.createWithFs()
        val tool = OpenProjectTool(store)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(OpenProjectTool.Input(path = "  "), ctx())
        }
    }
}
