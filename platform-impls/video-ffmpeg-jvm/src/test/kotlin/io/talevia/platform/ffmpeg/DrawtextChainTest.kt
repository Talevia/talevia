package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.platform.MediaPathResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-level verification of the `drawtext` filter chain emitted for subtitle
 * clips. No ffmpeg binary required — this only exercises the string formatter
 * so CI can run it everywhere.
 */
class DrawtextChainTest {
    private val engine = FfmpegVideoEngine(pathResolver = NullResolver)

    @Test
    fun emptyListReturnsNull() {
        assertNull(engine.subtitleDrawtextChain(emptyList(), 1920, 1080))
    }

    @Test
    fun basicClipRendersWhiteCenterBottomWithEnableGate() {
        val clip = textClip("hello", 1.0, 3.5)
        val chain = engine.subtitleDrawtextChain(listOf(clip), 1920, 1080)!!
        assertTrue(chain.startsWith("drawtext="), "chain should start with drawtext=")
        assertTrue(chain.contains("text='hello'"))
        assertTrue(chain.contains("fontsize=48"))
        assertTrue(chain.contains("fontcolor=0xFFFFFF"))
        assertTrue(chain.contains("x=(w-text_w)/2"))
        assertTrue(chain.contains("y=h-text_h-48"))
        assertTrue(chain.contains("enable=between(t\\,1\\,4.5)"))
        // No background color by default → no box.
        assertTrue(!chain.contains("box="))
    }

    @Test
    fun multipleClipsCommaJoinedInOrder() {
        val a = textClip("first", 0.0, 2.0)
        val b = textClip("second", 5.0, 2.0)
        val chain = engine.subtitleDrawtextChain(listOf(a, b), 1920, 1080)!!
        val parts = chain.split(",drawtext=")
        assertEquals(2, parts.size, "two subtitle clips should produce two drawtext filters")
        assertTrue(parts[0].contains("text='first'"))
        assertTrue(parts[1].contains("text='second'"))
    }

    @Test
    fun backgroundColorAddsBoxOpts() {
        val clip = textClip(
            "boxed",
            0.0,
            1.0,
            style = TextStyle(backgroundColor = "#000000"),
        )
        val chain = engine.subtitleDrawtextChain(listOf(clip), 1920, 1080)!!
        assertTrue(chain.contains("box=1"))
        assertTrue(chain.contains("boxcolor=0x000000"))
        assertTrue(chain.contains("boxborderw=10"))
    }

    @Test
    fun customFontSizeAndColorFlowThrough() {
        val clip = textClip(
            "styled",
            0.0,
            1.0,
            style = TextStyle(fontSize = 72f, color = "#112233"),
        )
        val chain = engine.subtitleDrawtextChain(listOf(clip), 1920, 1080)!!
        assertTrue(chain.contains("fontsize=72"))
        assertTrue(chain.contains("fontcolor=0x112233"))
    }

    @Test
    fun bottomMarginScalesWithOutputHeight() {
        val clip = textClip("m", 0.0, 1.0)
        val c1080 = engine.subtitleDrawtextChain(listOf(clip), 1920, 1080)!!
        val c540 = engine.subtitleDrawtextChain(listOf(clip), 960, 540)!!
        assertTrue(c1080.contains("y=h-text_h-48"))
        assertTrue(c540.contains("y=h-text_h-24"))
    }

    @Test
    fun bottomMarginNeverFallsBelowSafetyFloor() {
        // A 200-tall output would compute 200*48/1080 = 8 pixels — round up
        // to the 16px floor so captions don't glue to the frame edge on
        // tiny previews.
        val clip = textClip("m", 0.0, 1.0)
        val chain = engine.subtitleDrawtextChain(listOf(clip), 200, 200)!!
        assertTrue(chain.contains("y=h-text_h-16"))
    }

    @Test
    fun specialCharsInTextAreEscaped() {
        // Inside single quotes at graph level, `:` `,` `;` `[` `]` `\` are all
        // literal — no escape needed. Only `'` must break out via the
        // `'\''` idiom. `%` is escaped as `\%` so drawtext doesn't expand it.
        val clip = textClip("hi: it's %{now}, friend", 0.0, 1.0)
        val chain = engine.subtitleDrawtextChain(listOf(clip), 1920, 1080)!!
        assertTrue(
            chain.contains("text='hi: it'\\''s \\%{now}, friend'"),
            "expected graph-safe drawtext escaping, got chain: $chain",
        )
    }

    @Test
    fun backslashInTextIsLiteralNotDoubled() {
        // Inside single quotes backslash is a literal character — passing the
        // text through without doubling is correct per ffmpeg quoting rules.
        val clip = textClip("path\\to", 0.0, 1.0)
        val chain = engine.subtitleDrawtextChain(listOf(clip), 1920, 1080)!!
        assertTrue(chain.contains("text='path\\to'"))
    }

    @Test
    fun colorWithoutLeadingHashPassesThrough() {
        val clip = textClip("x", 0.0, 1.0, style = TextStyle(color = "red"))
        val chain = engine.subtitleDrawtextChain(listOf(clip), 1920, 1080)!!
        assertTrue(chain.contains("fontcolor=red"))
    }

    private fun textClip(
        text: String,
        startSec: Double,
        durationSec: Double,
        style: TextStyle = TextStyle(),
    ): Clip.Text = Clip.Text(
        id = ClipId("c-$text-$startSec"),
        timeRange = TimeRange(startSec.seconds, durationSec.seconds),
        text = text,
        style = style,
    )

    private object NullResolver : MediaPathResolver {
        override suspend fun resolve(assetId: AssetId): String = error("not used")
    }
}
