package io.talevia.desktop

import io.talevia.core.platform.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists
import kotlin.io.path.outputStream

/**
 * [SecretStore] that persists secrets to a Properties file under the user's
 * config dir (`~/.talevia/secrets.properties` by default). Not encrypted —
 * Desktop OS keychain integration (macOS `security`, Windows Credential Manager,
 * libsecret) is the proper next step; this impl exists so the composition root
 * can wire something concrete today.
 *
 * File mode is set to 0600 on POSIX systems so other users can't read the key
 * material.
 */
class FileSecretStore(
    private val path: Path = defaultPath(),
) : SecretStore {
    private val mutex = Mutex()

    override suspend fun get(key: String): String? = read { it.getProperty(key) }

    override suspend fun put(key: String, value: String) = write { it.setProperty(key, value) }

    override suspend fun remove(key: String) = write { it.remove(key) }

    override suspend fun keys(): Set<String> = read { it.stringPropertyNames().toSet() } ?: emptySet()

    private suspend fun <T> read(block: (Properties) -> T): T? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (path.notExists()) return@withContext null
            val props = Properties()
            path.inputStream().use(props::load)
            block(props)
        }
    }

    private suspend fun write(block: (Properties) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val props = Properties()
            if (path.exists()) path.inputStream().use(props::load)
            block(props)
            Files.createDirectories(path.parent)
            path.outputStream().use { props.store(it, "talevia secrets — do not edit by hand while the app is running") }
            restrictPermissions(path)
        }
    }

    private fun restrictPermissions(p: Path) {
        runCatching {
            val perms = PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(p, perms)
        }
    }

    private companion object {
        fun defaultPath(): Path {
            val home = System.getProperty("user.home") ?: "."
            return Path.of(home, ".talevia", "secrets.properties")
        }
    }
}
