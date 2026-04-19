package io.talevia.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.io.File

/**
 * In-app video preview for the desktop editor — closes the VISION §5.4
 * "agent 能跑出可看初稿" loop so users don't have to tab out to an external
 * player to see an export.
 *
 * Playback backend: JavaFX `MediaView` embedded via `JFXPanel` (a Swing
 * component) hosted inside Compose Desktop's `SwingPanel`. Reflection-based
 * loading means the rest of the app still compiles/runs even if JavaFX's
 * native libs happen to be missing (server builds, headless CI, tool
 * inspection, etc.) — the preview falls back to the "Open externally"
 * button in that case.
 *
 * Supported formats come from JavaFX: MP4 (H.264+AAC), M4A, FLV, FXM,
 * WAV, AIFF, MP3. That matches `ExportTool`'s current default (mp4 via
 * FFmpeg → H.264+AAC). If we ever ship an export profile with a codec
 * JavaFX can't decode, the fallback button still works.
 */
@Composable
fun VideoPreviewPanel(
    filePath: String?,
    modifier: Modifier = Modifier,
    /**
     * External seek requests. When [seekRequest] changes (new non-null value), the
     * JavaFX controller seeks to `seekRequest.second` seconds. The first member
     * is a monotonic counter so identical-timestamp clicks (same clip twice) still
     * re-fire — `remember`'s structural equality would otherwise swallow them.
     */
    seekRequest: Pair<Long, Double>? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = "Preview",
            modifier = Modifier.padding(bottom = 6.dp),
        )

        if (filePath == null) {
            PlaceholderCard("Run Export to produce a preview.")
            return@Column
        }
        val file = remember(filePath) { File(filePath) }
        if (!file.exists()) {
            PlaceholderCard("No file at $filePath — run Export.")
            return@Column
        }

        val fxAvailable = remember { JavaFxPreviewBackend.isAvailable() }
        if (fxAvailable) {
            JavaFxPreview(file = file, seekRequest = seekRequest)
        } else {
            PlaceholderCard("JavaFX runtime not available — preview disabled.")
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { openExternally(file) }) { Text("Open externally") }
            Spacer(Modifier.width(8.dp))
            Text(
                text = file.absolutePath,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PlaceholderCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFFEAEAEA)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun JavaFxPreview(file: File, seekRequest: Pair<Long, Double>?) {
    val controller = remember(file) { JavaFxPreviewBackend.create(file) }

    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var tick by remember { mutableStateOf(0) }

    SwingPanel(
        background = Color.Black,
        factory = { controller.createPanel() },
        modifier = Modifier.fillMaxWidth().height(260.dp),
    )

    // 10Hz polling of JavaFX media player state — simpler than hooking every
    // onReady / onEndOfMedia listener into Compose state.
    LaunchedEffect(file) {
        while (true) {
            tick++
            duration = controller.durationSeconds()
            position = controller.positionSeconds()
            playing = controller.isPlaying()
            kotlinx.coroutines.delay(100)
        }
    }

    // External seek trigger: observe changes to the (counter, seconds) pair and
    // forward to the JavaFX controller. Keyed on `file` too so seeks made
    // immediately after a preview swap still apply.
    LaunchedEffect(file, seekRequest) {
        if (seekRequest != null) controller.seekSeconds(seekRequest.second)
    }

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { controller.togglePlay() }) { Text(if (playing) "Pause" else "Play") }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatTime(position) + " / " + formatTime(duration),
            fontFamily = FontFamily.Monospace,
        )
    }
    if (duration > 0.0) {
        Slider(
            value = position.toFloat(),
            onValueChange = { controller.seekSeconds(it.toDouble()) },
            valueRange = 0f..duration.toFloat(),
        )
    }

    DisposableEffect(file) {
        onDispose { controller.dispose() }
    }
    // Suppress unused-tick warning in release builds — tick is the recompose driver.
    @Suppress("unused") val t = tick
}

private fun formatTime(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0.0) return "0:00.0"
    val whole = seconds.toInt()
    val m = whole / 60
    val s = whole % 60
    val tenths = ((seconds - whole) * 10).toInt().coerceIn(0, 9)
    return "%d:%02d.%d".format(m, s, tenths)
}

private fun openExternally(file: File) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    }
}

/**
 * Imperative bridge to JavaFX so the Compose layer doesn't statically reference
 * JavaFX types at all — the whole backend is wrapped behind a plain interface
 * that reflects classes on first call. Missing native libs surface as
 * `isAvailable() == false` rather than NoClassDefFoundError at composition time.
 */
interface JavaFxPreviewController {
    fun createPanel(): javax.swing.JComponent

    fun togglePlay()

    fun seekSeconds(seconds: Double)

    fun positionSeconds(): Double

    fun durationSeconds(): Double

    fun isPlaying(): Boolean

    fun dispose()
}
