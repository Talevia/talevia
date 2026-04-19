package io.talevia.desktop

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import java.io.File
import javax.swing.JComponent

/**
 * JavaFX-backed implementation of [JavaFxPreviewController].
 *
 * Split from [VideoPreview] so the Compose side never statically references
 * JavaFX classes. [isAvailable] probes the classpath + runtime before any
 * call path that would trigger class loading, so a Desktop build on a
 * system without native JavaFX libs degrades gracefully rather than
 * throwing `NoClassDefFoundError`.
 */
object JavaFxPreviewBackend {

    fun isAvailable(): Boolean =
        runCatching {
            // Class load + touching the toolkit is the real smoke test — JFXPanel's
            // constructor eagerly initialises the JavaFX toolkit and will throw if
            // native libs can't be found.
            Class.forName("javafx.embed.swing.JFXPanel", false, this::class.java.classLoader)
            Class.forName("javafx.scene.media.MediaPlayer", false, this::class.java.classLoader)
            true
        }.getOrElse { false }

    fun create(file: File): JavaFxPreviewController = JavaFxController(file)

    private class JavaFxController(private val file: File) : JavaFxPreviewController {
        private var panel: JFXPanel? = null
        private var player: MediaPlayer? = null
        private var view: MediaView? = null

        override fun createPanel(): JComponent {
            val p = JFXPanel()
            panel = p
            Platform.runLater { initialiseFxScene(p) }
            return p
        }

        private fun initialiseFxScene(p: JFXPanel) {
            runCatching {
                val media = Media(file.toURI().toString())
                val mp = MediaPlayer(media)
                val mv = MediaView(mp)
                val root = Group(mv)
                val scene = Scene(root)
                p.scene = scene

                // Fit the MediaView to the panel — otherwise it renders at the
                // media's native resolution and gets clipped by the Swing bounds.
                mv.fitWidthProperty().bind(scene.widthProperty())
                mv.fitHeightProperty().bind(scene.heightProperty())
                mv.isPreserveRatio = true

                player = mp
                view = mv
            }
        }

        override fun togglePlay() {
            Platform.runLater {
                val mp = player ?: return@runLater
                when (mp.status) {
                    MediaPlayer.Status.PLAYING -> mp.pause()
                    else -> mp.play()
                }
            }
        }

        override fun seekSeconds(seconds: Double) {
            Platform.runLater {
                val mp = player ?: return@runLater
                mp.seek(Duration.seconds(seconds))
            }
        }

        override fun positionSeconds(): Double = runCatching {
            player?.currentTime?.toSeconds() ?: 0.0
        }.getOrElse { 0.0 }

        override fun durationSeconds(): Double = runCatching {
            val d = player?.media?.duration ?: return 0.0
            if (d.isUnknown || d.isIndefinite) 0.0 else d.toSeconds()
        }.getOrElse { 0.0 }

        override fun isPlaying(): Boolean = runCatching {
            player?.status == MediaPlayer.Status.PLAYING
        }.getOrElse { false }

        override fun dispose() {
            Platform.runLater {
                runCatching { player?.dispose() }
                player = null
                view = null
                panel = null
            }
        }
    }
}
