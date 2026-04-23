package io.talevia.core.platform

/**
 * User-visible external filesystem abstraction. Explicitly NOT for Project
 * state (use [io.talevia.core.domain.ProjectStore]) and NOT for media assets
 * (use `import_media` + [io.talevia.core.domain.Project.assets]) — only for
 * letting the agent read/write a user's free-standing text files: subtitle
 * files (.srt / .vtt), prompt templates, edit scripts, story outlines,
 * anything living under the user's home dir that isn't already managed
 * Project state.
 *
 * Path semantics: raw `String`, absolute paths only, pre-validated with
 * [io.talevia.core.tool.PathGuard]. No custom `Path` wrapper — different
 * platforms (POSIX on JVM/iOS, content URIs on Android) disagree on what
 * a path even is, so we stay strings and let impls decide.
 *
 * Text-only on purpose. Binary artifacts go through `import_media`, which
 * handles metadata probing, mime-typing, and appends the asset to
 * `Project.assets`. Calling [readText] on binary content yields a clear
 * error instead of handing the LLM a byte bucket it can't make sense of.
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

    /**
     * Regex search over UTF-8 text files under [path]. [path] may be a
     * directory (walked recursively) or a single regular file. [pattern] is a
     * Kotlin [Regex] (Java-flavoured on JVM) applied per line. [include], when
     * non-null, is a simple glob on the file's absolute path that filters
     * which files are opened (e.g. `**.kt`). Files larger than [maxFileBytes]
     * or that fail UTF-8 decode are silently skipped — grep should never
     * error because one file is binary. [maxMatches] caps the returned list;
     * the caller sees `truncated=true` once that cap trips.
     */
    suspend fun grep(
        path: String,
        pattern: String,
        caseInsensitive: Boolean = false,
        include: String? = null,
        maxMatches: Int = DEFAULT_MAX_GREP_MATCHES,
        maxFileBytes: Long = DEFAULT_MAX_READ_BYTES,
    ): GrepResult

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

    data class GrepResult(
        val matches: List<GrepMatch>,
        val filesScanned: Int,
        val truncated: Boolean,
    )

    /**
     * A single matching line. `line` is 1-based. `content` is the full line
     * with trailing newline stripped; impls may truncate overly long lines.
     */
    data class GrepMatch(
        val path: String,
        val line: Int,
        val content: String,
    )

    companion object {
        /** 10 MB. Anything larger should go through `import_media` instead. */
        const val DEFAULT_MAX_READ_BYTES: Long = 10L * 1024 * 1024

        /** Guard against pathological directories; the LLM has no use for 10k entries. */
        const val DEFAULT_MAX_LIST_ENTRIES: Int = 1000

        /**
         * Grep cap. Picked to keep one agent turn's tool-result payload bounded:
         * at ~200 bytes per match line, 200 matches is ~40 KB — comfortably
         * replayable and still enough signal to drive follow-ups.
         */
        const val DEFAULT_MAX_GREP_MATCHES: Int = 200

        /**
         * Grep line-length cap. Very long lines are almost always minified
         * bundles or generated binary — we return the first N chars and elide
         * the rest so one such file can't blow the tool-result budget.
         */
        const val DEFAULT_GREP_LINE_CAP: Int = 512
    }
}
