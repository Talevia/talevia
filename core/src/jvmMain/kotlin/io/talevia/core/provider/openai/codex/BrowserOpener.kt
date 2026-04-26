package io.talevia.core.provider.openai.codex

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI

/**
 * Opens [url] in the user's default browser. Tries [Desktop.browse] first and
 * falls back to a per-OS shell command (`open` / `xdg-open` /
 * `rundll32 url.dll,FileProtocolHandler`). Returns true if a launcher was
 * dispatched; the actual browser navigation outcome is opaque (Java doesn't
 * report it).
 *
 * Note: we use `rundll32 url.dll,FileProtocolHandler` on Windows rather than
 * `cmd /c start` because `start` interprets `&` and `?` in the URL — fine for
 * trivial URLs but breaks PKCE/state authorize URLs that always contain both.
 */
internal object BrowserOpener {
    fun open(url: String): Boolean {
        if (!GraphicsEnvironment.isHeadless()) {
            val ok = runCatching {
                if (Desktop.isDesktopSupported()) {
                    val desktop = Desktop.getDesktop()
                    if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        desktop.browse(URI(url))
                        return@runCatching true
                    }
                }
                false
            }.getOrElse { false }
            if (ok) return true
        }
        return openWithShell(url)
    }

    private fun openWithShell(url: String): Boolean {
        val osName = (System.getProperty("os.name") ?: "").lowercase()
        val cmd = when {
            osName.contains("mac") || osName.contains("darwin") -> arrayOf("open", url)
            osName.contains("linux") || osName.contains("bsd") -> arrayOf("xdg-open", url)
            osName.contains("windows") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> return false
        }
        return runCatching {
            ProcessBuilder(*cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrElse { false }
    }
}
