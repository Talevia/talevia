package io.talevia.core.agent

import java.io.File

/**
 * JVM-side discovery of `AGENTS.md` / `CLAUDE.md` style project-instruction files.
 * Walks from [startDir] upward through parent directories (up to [maxWalkDepth]
 * levels, stopping at the filesystem root), collecting each matching file
 * exactly once. Optionally mixes in per-user global files so a machine-wide
 * `~/.claude/CLAUDE.md` or `~/.config/talevia/AGENTS.md` is picked up too.
 *
 * Ordering is outermost-first, innermost-last — so [formatProjectInstructionsSuffix]
 * puts the nearest (most specific) file at the tail of the system prompt where it
 * carries the most weight.
 *
 * Defensive size caps keep a stray 10 MB `AGENTS.md` from blowing out the
 * model's context:
 *  - [maxBytesPerFile] skips any single file larger than the cap
 *  - [maxTotalBytes] stops loading further files once the cumulative byte
 *    count crosses the cap (files already loaded are kept)
 *
 * Pure read path — never writes, never mutates the filesystem. Swallows I/O
 * errors per-file so a permission-denied on one globbed path doesn't nuke
 * discovery for the rest.
 */
object InstructionDiscovery {

    /** File names searched at every directory level, in priority order. */
    val DEFAULT_FILE_NAMES: List<String> = listOf("AGENTS.md", "CLAUDE.md")

    fun discover(
        startDir: File,
        includeGlobal: Boolean = true,
        fileNames: List<String> = DEFAULT_FILE_NAMES,
        maxWalkDepth: Int = 12,
        maxBytesPerFile: Long = 64 * 1024,
        maxTotalBytes: Long = 128 * 1024,
        home: File? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it) },
    ): List<ProjectInstruction> {
        val seen = mutableSetOf<String>()
        val collected = mutableListOf<ProjectInstruction>()
        var totalBytes = 0L

        fun tryLoad(file: File) {
            if (totalBytes >= maxTotalBytes) return
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
            if (!canonical.isFile) return
            val key = canonical.absolutePath
            if (!seen.add(key)) return
            val len = runCatching { canonical.length() }.getOrDefault(0L)
            if (len <= 0L || len > maxBytesPerFile) return
            if (totalBytes + len > maxTotalBytes) return
            val text = runCatching { canonical.readText(Charsets.UTF_8) }.getOrNull() ?: return
            if (text.isBlank()) return
            collected += ProjectInstruction(path = key, content = text)
            totalBytes += len
        }

        // Walk from root down toward startDir so the outer (less specific) files
        // land first and the innermost file is last.
        val chain = mutableListOf<File>()
        var cursor: File? = runCatching { startDir.canonicalFile }.getOrNull()
        var depth = 0
        while (cursor != null && depth < maxWalkDepth) {
            chain += cursor
            cursor = cursor.parentFile
            depth++
        }
        for (dir in chain.asReversed()) {
            for (name in fileNames) tryLoad(File(dir, name))
        }

        if (includeGlobal && home != null) {
            // Global files sit *before* the walk in the returned list so
            // project-specific files still win on conflict — but we emit them
            // after the walk here because both "outer" and "global" are less
            // specific than the project itself. Put globals first in the final
            // list by prepending.
            val globals = mutableListOf<ProjectInstruction>()
            val globalSpots = listOf(
                File(home, ".config/talevia/AGENTS.md"),
                File(home, ".talevia/AGENTS.md"),
                File(home, ".claude/CLAUDE.md"),
            )
            for (file in globalSpots) {
                if (totalBytes >= maxTotalBytes) break
                val before = collected.size
                tryLoad(file)
                if (collected.size > before) globals += collected.removeAt(collected.size - 1)
            }
            return globals + collected
        }
        return collected
    }
}
