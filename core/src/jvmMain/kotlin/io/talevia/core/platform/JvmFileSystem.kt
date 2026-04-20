package io.talevia.core.platform

import io.talevia.core.tool.PathGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * JVM [FileSystem] backed by `java.nio.file`. Every entry point:
 *  - runs [PathGuard.validate] (rejects traversal, NUL bytes, control chars)
 *  - hops to [Dispatchers.IO] (commonMain contract is `suspend`; callers may
 *    be on the main dispatcher from agent turns)
 *  - turns `IOException` / `CharacterCodingException` into [IllegalArgumentException]
 *    with a message the LLM can act on (e.g. "file too large: 42 MB > 10 MB cap")
 *
 * Not registered on iOS / Android — the `core.platform.FileSystem` interface
 * stays unimplemented on those platforms and the fs tools aren't registered in
 * their containers.
 */
class JvmFileSystem : FileSystem {
    override suspend fun readText(path: String, maxBytes: Long): String {
        PathGuard.validate(path, requireAbsolute = true)
        require(maxBytes > 0) { "maxBytes must be positive (got $maxBytes)" }
        return withContext(Dispatchers.IO) {
            val p = Paths.get(path)
            if (!Files.exists(p)) throw IllegalArgumentException("no such file: $path")
            if (!Files.isRegularFile(p)) throw IllegalArgumentException("not a regular file: $path")
            val size = Files.size(p)
            if (size > maxBytes) {
                throw IllegalArgumentException(
                    "file too large: $size bytes > $maxBytes byte cap at $path",
                )
            }
            val bytes = Files.readAllBytes(p)
            try {
                // Strict decoder: reject invalid UTF-8 instead of silently replacing.
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException(
                    "file is not valid UTF-8: $path — use import_media for binary assets",
                )
            }
        }
    }

    override suspend fun writeText(path: String, content: String, createDirs: Boolean): Long {
        PathGuard.validate(path, requireAbsolute = true)
        return withContext(Dispatchers.IO) {
            val p = Paths.get(path)
            if (createDirs) {
                p.parent?.let { Files.createDirectories(it) }
            } else if (p.parent != null && !Files.isDirectory(p.parent)) {
                throw IllegalArgumentException(
                    "parent directory does not exist: ${p.parent} (pass createDirs=true to mkdir -p)",
                )
            }
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            Files.write(
                p,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            bytes.size.toLong()
        }
    }

    override suspend fun list(path: String, maxEntries: Int): FileSystem.DirectoryListing {
        PathGuard.validate(path, requireAbsolute = true)
        require(maxEntries > 0) { "maxEntries must be positive (got $maxEntries)" }
        return withContext(Dispatchers.IO) {
            val p = Paths.get(path)
            if (!Files.exists(p)) throw IllegalArgumentException("no such directory: $path")
            if (!Files.isDirectory(p)) throw IllegalArgumentException("not a directory: $path")
            val all = Files.list(p).use { stream ->
                stream.iterator().asSequence().toList()
            }.sortedBy { it.fileName.toString() }
            val truncated = all.size > maxEntries
            val kept = all.take(maxEntries).map { child -> toEntry(child) }
            FileSystem.DirectoryListing(entries = kept, truncated = truncated)
        }
    }

    override suspend fun glob(pattern: String, maxMatches: Int): FileSystem.GlobResult {
        PathGuard.validate(pattern, requireAbsolute = true)
        require(maxMatches > 0) { "maxMatches must be positive (got $maxMatches)" }
        return withContext(Dispatchers.IO) {
            val root = globWalkRoot(pattern)
            if (!Files.exists(root)) {
                throw IllegalArgumentException("glob root does not exist: $root")
            }
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val collected = mutableListOf<String>()
            var truncated = false
            Files.walk(root).use { stream ->
                val iter = stream.iterator()
                var stopped = false
                while (iter.hasNext() && !stopped) {
                    val candidate = iter.next()
                    if (matcher.matches(candidate)) {
                        if (collected.size >= maxMatches) {
                            truncated = true
                            stopped = true
                        } else {
                            collected += candidate.toString()
                        }
                    }
                }
            }
            FileSystem.GlobResult(matches = collected.sorted(), truncated = truncated)
        }
    }

    private fun toEntry(child: Path): FileSystem.Entry {
        val attrs = try {
            Files.readAttributes(child, BasicFileAttributes::class.java)
        } catch (_: IOException) {
            return FileSystem.Entry(
                name = child.fileName.toString(),
                isDirectory = false,
                sizeBytes = 0,
                modifiedEpochMs = 0,
            )
        }
        return FileSystem.Entry(
            name = child.fileName.toString(),
            isDirectory = attrs.isDirectory,
            sizeBytes = if (attrs.isRegularFile) attrs.size() else 0,
            modifiedEpochMs = attrs.lastModifiedTime().toMillis(),
        )
    }

    /**
     * Pick a concrete filesystem directory to root the walk at. We take the
     * longest leading prefix whose segments contain no glob metacharacter
     * (star, question mark, open bracket, open brace). For a pattern like
     * `/Users/xxx/Downloads` plus a double-star segment plus `.srt` suffix,
     * that's `/Users/xxx/Downloads`; for `/tmp` plus a `.txt` star-segment
     * it's `/tmp`. If the whole pattern is literal we just walk its parent.
     */
    private fun globWalkRoot(pattern: String): Path {
        // Absolute paths split with a leading empty segment (e.g. "/tmp/x/*.srt" →
        // ["", "tmp", "x", "*.srt"]). Take the leading run of segments that have
        // no glob metacharacter; the first segment is always "", which stays. For
        // "/tmp/x/*.srt" → ["", "tmp", "x"] → "/tmp/x". For "/tmp/**/foo.srt" →
        // ["", "tmp"] → "/tmp". Without this guard the walk would start at "/"
        // and hit permission errors on /usr/sbin etc.
        val segments = pattern.split('/')
        val staticSegs = segments.takeWhile { seg -> seg.none(::isGlobMetaChar) }
        val rootPath = staticSegs.joinToString("/").ifEmpty { "/" }
        return Paths.get(if (rootPath.isEmpty()) "/" else rootPath)
    }

    /** Chars that start a glob pattern segment; see [globWalkRoot]. */
    private fun isGlobMetaChar(c: Char): Boolean =
        c.code == 42 || c.code == 63 || c.code == 91 || c.code == 123
}
