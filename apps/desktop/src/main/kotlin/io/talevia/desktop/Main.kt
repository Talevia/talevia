package io.talevia.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.talevia.core.logging.LogLevel
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.info
import kotlin.uuid.ExperimentalUuidApi

/**
 * Compose Desktop entry point.
 *
 * M2 scope: a wiring demo, not a finished editor. Three panels — assets /
 * timeline / chat — show the round-trip from "register tool" to "tool
 * dispatch" to "store mutation" without yet invoking the LLM (provider
 * keys aren't in scope here).
 *
 * Everything non-trivial lives in sibling files in the same package:
 *  - [AppRoot] — three-column composition root.
 *  - [ChatPanel] — agent-driven chat surface + session switcher.
 *  - [DesktopShortcutHolder] — window-level cmd+E/S/R key dispatch.
 *  - [ResolutionDropdown] / [FpsDropdown] — export preset UI.
 *    (Provider-default model picker now lives in
 *    `io.talevia.core.provider.defaultModelFor` since cycle 274's
 *    `debt-consolidate-defaultModelFor-three-copies`.)
 *  - [SectionTitle] / [openExternallyIfExists] / [resolveOpenablePath] /
 *    [desktopEnvWithDefaults] — shared helpers.
 */
@OptIn(ExperimentalUuidApi::class)
fun main() = application {
    Loggers.setMinLevel(LogLevel.DEBUG)
    Loggers.get("desktop").info("boot")
    val container = remember { AppContainer(desktopEnvWithDefaults()) }
    // Holder for window-level keyboard shortcuts. AppRoot mutates the action
    // fields from its composition scope (where the export / snapshot state is
    // in scope); the Window's onKeyEvent consults the holder on every key.
    val shortcuts = remember { DesktopShortcutHolder() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Talevia",
        state = rememberWindowState(width = 1260.dp, height = 820.dp),
        onKeyEvent = { shortcuts.handle(it) },
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppRoot(container = container, shortcuts = shortcuts)
            }
        }
    }
}
