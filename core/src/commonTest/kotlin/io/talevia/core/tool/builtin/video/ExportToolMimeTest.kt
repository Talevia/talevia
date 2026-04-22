package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
import io.talevia.core.domain.Timeline
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

class ExportToolMimeTest {
    private val tool = ExportTool(store = ThrowingProjectStore, engine = ThrowingVideoEngine)

    private object ThrowingProjectStore : ProjectStore {
        override suspend fun get(id: ProjectId): Project? = error("not used")
        override suspend fun upsert(title: String, project: Project) = error("not used")
        override suspend fun list(): List<Project> = error("not used")
        override suspend fun delete(id: ProjectId, deleteFiles: Boolean) = error("not used")
        override suspend fun setTitle(id: ProjectId, title: String) = error("not used")
        override suspend fun summary(id: ProjectId): ProjectSummary? = error("not used")
        override suspend fun listSummaries(): List<ProjectSummary> = error("not used")
        override suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project = error("not used")
    }

    private object ThrowingVideoEngine : VideoEngine {
        override suspend fun probe(source: MediaSource): MediaMetadata = error("not used")
        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> = emptyFlow()
        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = error("not used")
    }

    @Test
    fun knownVideoExtensionsMapToConcreteMime() {
        assertEquals("video/mp4", tool.mimeTypeFor("/tmp/out.mp4"))
        assertEquals("video/mp4", tool.mimeTypeFor("x.M4V"))
        assertEquals("video/quicktime", tool.mimeTypeFor("x.MOV"))
        assertEquals("video/webm", tool.mimeTypeFor("a/b/c.webm"))
        assertEquals("video/x-matroska", tool.mimeTypeFor("x.mkv"))
        assertEquals("video/x-msvideo", tool.mimeTypeFor("X.AVI"))
    }

    @Test
    fun imageAndAudioExtensionsRecognised() {
        assertEquals("image/gif", tool.mimeTypeFor("out.gif"))
        assertEquals("audio/mpeg", tool.mimeTypeFor("out.mp3"))
        assertEquals("audio/mp4", tool.mimeTypeFor("out.m4a"))
        assertEquals("audio/wav", tool.mimeTypeFor("out.wav"))
    }

    @Test
    fun unknownExtensionFallsBackToOctetStream() {
        assertEquals("application/octet-stream", tool.mimeTypeFor("out.xyz"))
        assertEquals("application/octet-stream", tool.mimeTypeFor("noextension"))
    }
}
