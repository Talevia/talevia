package io.talevia.desktop

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Window-level keyboard shortcut holder. `AppRoot` registers callbacks via
 * [install]; the `Window`'s `onKeyEvent` invokes [handle] which dispatches
 * to the current callback when the binding matches. Keep the surface small
 * — adding a shortcut = one field + one case in [handle].
 *
 * Bindings:
 *   cmd+E / ctrl+E — Export
 *   cmd+S / ctrl+S — Save snapshot
 *   cmd+R / ctrl+R — Regenerate stale clips for the active project
 */
internal class DesktopShortcutHolder {
    var export: () -> Unit = {}
    var saveSnapshot: () -> Unit = {}
    var regenerateStale: () -> Unit = {}

    fun install(
        export: () -> Unit,
        saveSnapshot: () -> Unit,
        regenerateStale: () -> Unit,
    ) {
        this.export = export
        this.saveSnapshot = saveSnapshot
        this.regenerateStale = regenerateStale
    }

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val cmdOrCtrl = event.isMetaPressed || event.isCtrlPressed
        if (!cmdOrCtrl) return false
        return when (event.key) {
            androidx.compose.ui.input.key.Key.E -> { export(); true }
            androidx.compose.ui.input.key.Key.S -> { saveSnapshot(); true }
            androidx.compose.ui.input.key.Key.R -> { regenerateStale(); true }
            else -> false
        }
    }
}
