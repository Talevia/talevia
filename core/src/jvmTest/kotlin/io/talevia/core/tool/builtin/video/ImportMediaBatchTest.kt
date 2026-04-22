package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the `batch-asset-import` cycle — `ImportMediaTool` accepts either
 * a single `path` (legacy shape) or a `paths: List<String>` (new batch
 * shape). Batch mode captures per-path successes + failures without
 * aborting, so one bad clip in a 40-file rsync doesn't lose the other 39.
 */
class ImportMediaBatchTest {

    /** Probes succeed for files ending in `.good`, fail for `.bad`. */
    private class SelectiveVideoEngine : VideoEngine {
        override suspend fun probe(source: MediaSource): MediaMetadata {
            val path = (source as MediaSource.File).path
            if (path.endsWith(".bad")) error("simulated probe failure for $path")
            return MediaMetadata(
                duration = 5.seconds,
                resolution = Resolution(1280, 720),
                videoCodec = "h264",
            )
        }
        override fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress> =
            flowOf(RenderProgress.Failed("no-op", "stub"))
        override suspend fun thumbnail(
            asset: AssetId,
            source: MediaSource,
            time: Duration,
        ): ByteArray = ByteArray(0)
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun rig(): Triple<SqlDelightProjectStore, InMemoryMediaStorage, ImportMediaTool> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val projects = SqlDelightProjectStore(TaleviaDb(driver))
        projects.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        val storage = InMemoryMediaStorage()
        val tool = ImportMediaTool(storage, SelectiveVideoEngine(), projects)
        return Triple(projects, storage, tool)
    }

    private fun tempPath(suffix: String): String {
        val tmp = createTempDirectory("import-batch-test")
        val f = Files.createFile(tmp.resolve("file$suffix"))
        return f.toAbsolutePath().toString()
    }

    @Test fun singlePathPreservesLegacyShape() = runTest {
        val (_, _, tool) = rig()
        val path = tempPath(".good")
        val out = tool.execute(
            ImportMediaTool.Input(path = path, projectId = "p"),
            ctx(),
        ).data
        // Flat fields populated.
        assertTrue(out.assetId.isNotEmpty())
        assertEquals(5.0, out.durationSeconds)
        assertEquals(1280, out.width)
        // Batch lists reflect the single import.
        assertEquals(1, out.imported.size)
        assertEquals(path, out.imported.single().path)
        assertEquals(out.assetId, out.imported.single().assetId)
        assertEquals(0, out.failed.size)
    }

    @Test fun batchImportsMultipleFilesAndPopulatesListed() = runTest {
        val (projects, _, tool) = rig()
        val paths = (0 until 3).map { tempPath(".good") }
        val out = tool.execute(
            ImportMediaTool.Input(paths = paths, projectId = "p"),
            ctx(),
        ).data
        assertEquals(3, out.imported.size)
        assertEquals(0, out.failed.size)
        // Project now contains 3 assets.
        val storedAssets = projects.get(ProjectId("p"))!!.assets
        assertEquals(3, storedAssets.size)
        // Flat assetId points at the first successful import.
        assertEquals(out.imported.first().assetId, out.assetId)
    }

    @Test fun batchCapturesPerPathFailuresWithoutAbortingTheRun() = runTest {
        val (projects, _, tool) = rig()
        val good1 = tempPath(".good")
        val bad1 = tempPath(".bad")
        val good2 = tempPath(".good")
        val bad2 = tempPath(".bad")
        val good3 = tempPath(".good")

        val out = tool.execute(
            ImportMediaTool.Input(paths = listOf(good1, bad1, good2, bad2, good3), projectId = "p"),
            ctx(),
        ).data

        assertEquals(3, out.imported.size, "good paths must all succeed despite sibling failures")
        assertEquals(2, out.failed.size)
        assertEquals(setOf(bad1, bad2), out.failed.map { it.path }.toSet())
        assertTrue(out.failed.all { "simulated probe failure" in it.error })
        // Project only holds the successful imports.
        val storedAssets = projects.get(ProjectId("p"))!!.assets
        assertEquals(3, storedAssets.size)
    }

    @Test fun allFailedBatchReturnsEmptyFlatAssetId() = runTest {
        val (projects, _, tool) = rig()
        val bad = (0 until 3).map { tempPath(".bad") }
        val out = tool.execute(
            ImportMediaTool.Input(paths = bad, projectId = "p"),
            ctx(),
        ).data
        assertEquals(0, out.imported.size)
        assertEquals(3, out.failed.size)
        assertEquals("", out.assetId, "flat assetId must be empty when nothing imported")
        assertEquals(0.0, out.durationSeconds)
        assertEquals(0, projects.get(ProjectId("p"))!!.assets.size)
    }

    @Test fun bothPathAndPathsRejected() = runTest {
        val (_, _, tool) = rig()
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ImportMediaTool.Input(
                    path = tempPath(".good"),
                    paths = listOf(tempPath(".good")),
                    projectId = "p",
                ),
                ctx(),
            )
        }
        assertTrue("not both" in ex.message.orEmpty())
    }

    @Test fun neitherPathNorPathsRejected() = runTest {
        val (_, _, tool) = rig()
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ImportMediaTool.Input(projectId = "p"),
                ctx(),
            )
        }
        assertTrue("must supply" in ex.message.orEmpty())
    }

    @Test fun emptyPathsListRejected() = runTest {
        val (_, _, tool) = rig()
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ImportMediaTool.Input(paths = emptyList(), projectId = "p"),
                ctx(),
            )
        }
    }

    @Test fun duplicatePathsRejected() = runTest {
        val (_, _, tool) = rig()
        val p = tempPath(".good")
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ImportMediaTool.Input(paths = listOf(p, p), projectId = "p"),
                ctx(),
            )
        }
    }
}
