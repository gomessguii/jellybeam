package tv.jellybeam.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the keyboard volume keys: mpv's defaults plus the `-`/`=` pair, and nothing the player already uses. */
class AppVolumeKeysTest {
    @Test
    fun `minus and slash lower, equals and star raise`() {
        listOf(Key.Equals, Key.Plus, Key.NumPadAdd, Key.Multiply, Key.NumPadMultiply).forEach { assertEquals("$it", 1, appVolumeKeyDirection(it)) }
        listOf(Key.Minus, Key.NumPadSubtract, Key.Slash, Key.NumPadDivide).forEach { assertEquals("$it", -1, appVolumeKeyDirection(it)) }
    }

    @Test
    fun `navigation, transport and paging keys are left alone`() {
        listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionCenter, Key.MediaPlayPause, Key.ChannelUp, Key.PageDown, Key.VolumeUp)
            .forEach { assertNull("$it", appVolumeKeyDirection(it)) }
    }
}
