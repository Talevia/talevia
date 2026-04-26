package io.talevia.core.provider.openai.codex

import io.talevia.core.JsonConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.readText

/**
 * On-disk store for [OpenAiCodexCredentials] backed by a single JSON file at
 * `~/.talevia/openai-codex-auth.json` (POSIX `0600`). Sits next to
 * `secrets.properties` but separate because the credential schema is structured
 * — squeezing nested OAuth tokens into a flat properties file would invite drift.
 */
class FileOpenAiCodexCredentialStore(
    private val path: Path = defaultPath(),
    private val json: Json = JsonConfig.default,
) : OpenAiCodexCredentialStore {

    private val mutex = Mutex()

    override suspend fun load(): OpenAiCodexCredentials? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (path.notExists()) return@withContext null
            runCatching {
                json.decodeFromString(OpenAiCodexCredentials.serializer(), path.readText())
            }.getOrNull()
        }
    }

    override suspend fun save(creds: OpenAiCodexCredentials) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val parent = path.parent
            if (parent != null && !parent.exists()) Files.createDirectories(parent)
            val text = json.encodeToString(OpenAiCodexCredentials.serializer(), creds)
            // Truncate-write atomically; a partial write on crash is acceptable
            // since we'll just prompt the user to /login again.
            Files.writeString(
                path,
                text,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            restrictPermissions(path)
        }
    }

    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            path.deleteIfExists()
            Unit
        }
    }

    private fun restrictPermissions(p: Path) {
        runCatching {
            // POSIX-only — no-op on Windows file systems that don't support it.
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        fun defaultPath(): Path {
            val home = System.getProperty("user.home") ?: "."
            return Path.of(home, ".talevia", "openai-codex-auth.json")
        }
    }
}
