package io.talevia.core.platform

/**
 * User-visible external filesystem abstraction. Explicitly NOT for Project
 * state (use [io.talevia.core.domain.ProjectStore]) and NOT for media assets
 * (use [MediaStorage]) — only for letting the agent read/write a user's
 * free-standing text files: subtitle files (.srt / .vtt), prompt templates,
 * edit scripts, story outlines, anything living under the user's home dir
 * that isn't already managed Project state.
 *
 * Path semantics mirror [MediaStorage]: raw `String`, absolute paths only,
 * pre-validated with [io.talevia.core.tool.PathGuard]. No custom `Path`
 * wrapper — different platforms (POSIX on JVM/iOS, content URIs on Android)
 * disagree on what a path even is, so we stay strings and let impls decide.
 *
 * Text-only on purpose. Binary artifacts go through `import_media` +
 * [MediaStorage] where metadata probing, mime-typing, and the asset catalog
 * live. Calling [readText] on binary content yields a clear error instead of
 * handing the LLM a byte bucket it can't make sense of.
 *
 * All methods are `suspend` so impls can off-load to an I/O dispatcher.
 */
interface FileSystem {
    /**
     * Read a UTF-8 text file and return its full content. Fails if the file
     * is larger than [maxBytes], doesn't exist, is not a regular file, or
     * isn't decodable as UTF-8. Errors carry the offending path and (when
     * relevant) the observed size so the agent can course-correct.
     */
    suspend fun readText(path: String, maxBytes: Long = DEFAULT_MAX_READ_BYTES): String

    /**
     * Write [content] to [path] as UTF-8, truncating any existing file.
     * `createDirs=true` creates missing parent directories (`mkdir -p`).
     * Returns the number of bytes actually written.
     */
    suspend fun writeText(path: String, content: String, createDirs: Boolean = false): Long

    /**
     * Non-recursive directory listing. Returns up to [maxEntries] entries
     * sorted by name; `truncated=true` means there were more we dropped.
     * Fails if [path] isn't a directory.
     */
    suspend fun list(path: String, maxEntries: Int = DEFAULT_MAX_LIST_ENTRIES): DirectoryListing

    /**
     * Glob match against the filesystem. `pattern` uses the platform's default
     * glob syntax (POSIX globs on JVM: `*` = one segment, `**` = across
     * directories, `?` = single char). Pattern must be absolute so the walk
     * root is unambiguous.
     */
    suspend fun glob(pattern: String, maxMatches: Int = DEFAULT_MAX_LIST_ENTRIES): GlobResult

    data class DirectoryListing(
        val entries: List<Entry>,
        val truncated: Boolean,
    )

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedEpochMs: Long,
    )

    data class GlobResult(
        val matches: List<String>,
        val truncated: Boolean,
    )

    companion object {
        /** 10 MB. Anything larger should go through [MediaStorage] instead. */
        const val DEFAULT_MAX_READ_BYTES: Long = 10L * 1024 * 1024

        /** Guard against pathological directories; the LLM has no use for 10k entries. */
        const val DEFAULT_MAX_LIST_ENTRIES: Int = 1000
    }
}
