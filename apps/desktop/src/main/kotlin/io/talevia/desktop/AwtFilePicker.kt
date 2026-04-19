package io.talevia.desktop

import io.talevia.core.domain.MediaSource
import io.talevia.core.platform.FileFilter
import io.talevia.core.platform.FilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * AWT-backed [FilePicker] for Compose Desktop. Uses the native system dialog on
 * macOS/Windows and GTK on Linux. All dialog calls hop to `Dispatchers.IO` because
 * `FileDialog.setVisible(true)` blocks until dismissed.
 */
class AwtFilePicker : FilePicker {
    override suspend fun pick(filter: FileFilter, title: String?): MediaSource? =
        withContext(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, title ?: "Choose a file", FileDialog.LOAD).apply {
                isMultipleMode = false
                applyFilter(this, filter)
                isVisible = true
            }
            dialog.toSources().firstOrNull()
        }

    override suspend fun pickMultiple(filter: FileFilter, title: String?): List<MediaSource> =
        withContext(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, title ?: "Choose files", FileDialog.LOAD).apply {
                isMultipleMode = true
                applyFilter(this, filter)
                isVisible = true
            }
            dialog.toSources()
        }

    private fun applyFilter(dialog: FileDialog, filter: FileFilter) {
        val extensions = when (filter) {
            FileFilter.Any -> null
            FileFilter.Video -> setOf("mp4", "mov", "m4v", "webm", "mkv", "avi")
            FileFilter.Audio -> setOf("mp3", "m4a", "wav", "flac", "ogg", "aac")
            FileFilter.Image -> setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
        }
        if (extensions != null) {
            dialog.setFilenameFilter { _, name ->
                name.substringAfterLast('.', "").lowercase() in extensions
            }
        }
    }

    private fun FileDialog.toSources(): List<MediaSource> {
        val multi = files?.map { MediaSource.File(it.absolutePath) }.orEmpty()
        if (multi.isNotEmpty()) return multi
        val dir = directory ?: return emptyList()
        val name = file ?: return emptyList()
        return listOf(MediaSource.File(File(dir, name).absolutePath))
    }
}
