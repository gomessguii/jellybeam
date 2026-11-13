package tv.jellybeam.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins [resolveAudioRoute]: the headphone-vs-speaker decision headphone-only processing keys off. */
class AudioOutputRouteTest {

    @Test
    fun `headphone-like outputs classify as headphones`() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        ).forEach { assertEquals("type $it", AudioOutputKind.HEADPHONES, classifyAudioDevice(it)) }
    }

    @Test
    fun `built-in speakers and receivers are not headphones`() {
        assertEquals(AudioOutputKind.SPEAKERS, classifyAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(AudioOutputKind.EXTERNAL, classifyAudioDevice(AudioDeviceInfo.TYPE_HDMI_ARC))
        assertEquals(AudioOutputKind.EXTERNAL, classifyAudioDevice(AudioDeviceInfo.TYPE_HDMI_EARC))
        assertEquals(AudioOutputKind.EXTERNAL, classifyAudioDevice(AudioDeviceInfo.TYPE_LINE_DIGITAL))
        assertEquals(AudioOutputKind.UNKNOWN, classifyAudioDevice(AudioDeviceInfo.TYPE_UNKNOWN))
    }

    @Test
    fun `a connected USB headset outranks the built-in speaker`() {
        val connected = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_USB_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, predictMediaRoute(connected))
        assertEquals(
            AudioRoute(AudioOutputKind.HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, RouteSource.PREDICTED),
            resolveAudioRoute(trackDevice = null, platformDevice = null, connected = connected),
        )
    }

    @Test
    fun `speaker alone predicts speakers`() {
        assertEquals(
            AudioOutputKind.SPEAKERS,
            resolveAudioRoute(null, null, listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).kind,
        )
    }

    @Test
    fun `the live track's routed device beats the platform and the prediction`() {
        val route = resolveAudioRoute(
            trackDevice = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            platformDevice = AudioDeviceInfo.TYPE_USB_HEADSET,
            connected = listOf(AudioDeviceInfo.TYPE_USB_HEADSET),
        )
        assertEquals(AudioRoute(AudioOutputKind.SPEAKERS, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, RouteSource.TRACK), route)
    }

    @Test
    fun `the platform media route beats the prediction`() {
        val route = resolveAudioRoute(
            trackDevice = null,
            platformDevice = AudioDeviceInfo.TYPE_HDMI_ARC,
            connected = listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HDMI_ARC),
        )
        assertEquals(RouteSource.PLATFORM, route.source)
        assertEquals(AudioOutputKind.EXTERNAL, route.kind)
    }

    @Test
    fun `nothing connected resolves to NONE`() {
        assertNull(predictMediaRoute(emptyList()))
        assertEquals(AudioRoute.NONE, resolveAudioRoute(null, null, emptyList()))
    }

    @Test
    fun `an unlisted connected type is still predicted rather than dropped`() {
        assertEquals(AudioDeviceInfo.TYPE_IP, predictMediaRoute(listOf(AudioDeviceInfo.TYPE_IP)))
    }
}
