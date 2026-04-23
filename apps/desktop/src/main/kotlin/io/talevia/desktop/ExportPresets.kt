package io.talevia.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/**
 * Export resolution preset. `Project` means "use the project's OutputProfile"
 * (no override); concrete presets force width/height on the Export tool
 * input. `HD720` intentionally names the resolution, not a vendor label.
 */
internal enum class ResolutionPreset(val label: String, val width: Int?, val height: Int?) {
    Project("Project", null, null),
    HD720("720p", 1280, 720),
    FullHD("1080p", 1920, 1080),
    UHD4K("4K", 3840, 2160),
}

internal enum class FpsPreset(val label: String, val value: Int?) {
    Project("Project fps", null),
    Fps24("24", 24),
    Fps30("30", 30),
    Fps60("60", 60),
}

@Composable
internal fun ResolutionDropdown(selected: ResolutionPreset, onSelect: (ResolutionPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${selected.label} ▾", fontFamily = FontFamily.Monospace)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ResolutionPreset.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { onSelect(p); open = false },
                )
            }
        }
    }
}

@Composable
internal fun FpsDropdown(selected: FpsPreset, onSelect: (FpsPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${selected.label} ▾", fontFamily = FontFamily.Monospace)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FpsPreset.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { onSelect(p); open = false },
                )
            }
        }
    }
}

/**
 * Default model id per provider, for the Chat panel's first turn when the
 * user hasn't explicitly chosen a model.
 */
internal fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-5.4-mini"
    else -> "default"
}
