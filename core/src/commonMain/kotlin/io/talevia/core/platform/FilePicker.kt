package io.talevia.core.platform

/**
 * Cross-platform handle to the system file picker. Each platform implements this
 * with its native UI: Desktop uses AWT `FileDialog`, iOS opens `PHPickerViewController`,
 * Android launches `ACTION_OPEN_DOCUMENT`, Server has no implementation.
 *
 * The Core never loads file bytes through this API — it only receives a
 * [io.talevia.core.domain.MediaSource] token (platform URI or local path) that
 * `import_media` then persists into the active project bundle.
 */
interface FilePicker {
    /**
     * Open a single-selection picker filtered by [filter]. Suspends until the user
     * dismisses the dialog; returns null on cancel.
     */
    suspend fun pick(filter: FileFilter = FileFilter.Any, title: String? = null): io.talevia.core.domain.MediaSource?

    /**
     * Open a multi-selection picker. Returns an empty list when the user cancels
     * or the platform doesn't support multi-select.
     */
    suspend fun pickMultiple(filter: FileFilter = FileFilter.Any, title: String? = null): List<io.talevia.core.domain.MediaSource>
}

/** High-level content filter; the platform translates to its native MIME/UTI list. */
enum class FileFilter { Any, Video, Audio, Image }
