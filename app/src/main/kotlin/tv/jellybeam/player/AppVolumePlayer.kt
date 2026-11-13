@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.jellybeam.player

import android.os.Handler
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArrayList
import tv.jellybeam.audio.AudioDsp
import tv.jellybeam.audio.HeadphoneVolume

/** The device-volume commands a session player gains while the app owns the volume. */
internal val APP_VOLUME_COMMANDS: IntArray = intArrayOf(
    Player.COMMAND_GET_DEVICE_VOLUME,
    Player.COMMAND_SET_DEVICE_VOLUME,
    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
    Player.COMMAND_ADJUST_DEVICE_VOLUME,
    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
)

/**
 * While headphones are the route, reports the session as remote playback whose volume is the app's
 * headphone volume ([AudioDsp]), so the platform sends the remote's volume keys here instead of to
 * a TV volume the DAC never hears; on any other route it is the wrapped player untouched. Sits
 * under the session's routing player, which must allow [APP_VOLUME_COMMANDS].
 */
internal class AppVolumePlayer(player: Player, private val dsp: AudioDsp?) : ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private val handler = Handler(player.applicationLooper)
    private var publishedInfo = deviceInfo
    private var publishedVolume = deviceVolume
    private var publishedOwns = owns

    /** The level Unmute returns to. */
    private var unmuteTo = HeadphoneVolume.MAX

    private val onDspChange: () -> Unit = { handler.post(::publish) }

    init {
        dsp?.addListener(onDspChange)
    }

    /** Stops following [AudioDsp]; call when the session is released. */
    fun detach() {
        dsp?.removeListener(onDspChange)
        handler.removeCallbacksAndMessages(null)
    }

    private val owns: Boolean get() = dsp?.ownsVolume == true

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getDeviceInfo(): DeviceInfo =
        if (owns) {
            DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(HeadphoneVolume.MAX).build()
        } else {
            super.getDeviceInfo()
        }

    override fun getDeviceVolume(): Int = dsp?.takeIf { owns }?.headphoneVolume ?: super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean = if (owns) dsp?.headphoneVolume == 0 else super.isDeviceMuted()

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (!owns) return base
        return base.buildUpon().addAll(*APP_VOLUME_COMMANDS).build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        (owns && command in APP_VOLUME_COMMANDS) || super.isCommandAvailable(command)

    override fun setDeviceVolume(volume: Int, flags: Int) = ifOwned({ setTo(volume) }) { super.setDeviceVolume(volume, flags) }

    @Deprecated("Deprecated in Player")
    @Suppress("DEPRECATION")
    override fun setDeviceVolume(volume: Int) = ifOwned({ setTo(volume) }) { super.setDeviceVolume(volume) }

    override fun increaseDeviceVolume(flags: Int) = ifOwned({ step(1) }) { super.increaseDeviceVolume(flags) }

    @Deprecated("Deprecated in Player")
    @Suppress("DEPRECATION")
    override fun increaseDeviceVolume() = ifOwned({ step(1) }) { super.increaseDeviceVolume() }

    override fun decreaseDeviceVolume(flags: Int) = ifOwned({ step(-1) }) { super.decreaseDeviceVolume(flags) }

    @Deprecated("Deprecated in Player")
    @Suppress("DEPRECATION")
    override fun decreaseDeviceVolume() = ifOwned({ step(-1) }) { super.decreaseDeviceVolume() }

    override fun setDeviceMuted(muted: Boolean, flags: Int) = ifOwned({ mute(muted) }) { super.setDeviceMuted(muted, flags) }

    @Deprecated("Deprecated in Player")
    @Suppress("DEPRECATION")
    override fun setDeviceMuted(muted: Boolean) = ifOwned({ mute(muted) }) { super.setDeviceMuted(muted) }

    private inline fun ifOwned(owned: () -> Unit, otherwise: () -> Unit) = if (owns) owned() else otherwise()

    private fun setTo(volume: Int) {
        dsp?.setHeadphoneVolume(volume)
    }

    private fun step(delta: Int) {
        dsp?.stepHeadphoneVolume(delta)
    }

    private fun mute(muted: Boolean) {
        val dsp = dsp ?: return
        if (muted) {
            if (dsp.headphoneVolume > 0) unmuteTo = dsp.headphoneVolume
            dsp.setHeadphoneVolume(0)
        } else if (dsp.headphoneVolume == 0) {
            dsp.setHeadphoneVolume(unmuteTo)
        }
    }

    /** Tells the session what changed, on the player's looper, so it moves the volume keys and its UI along. */
    private fun publish() {
        val info = deviceInfo
        val volume = deviceVolume
        val ownsNow = owns
        if (ownsNow != publishedOwns) {
            publishedOwns = ownsNow
            val commands = availableCommands
            listeners.forEach { it.onAvailableCommandsChanged(commands) }
        }
        if (info != publishedInfo) {
            publishedInfo = info
            listeners.forEach { it.onDeviceInfoChanged(info) }
        }
        if (volume != publishedVolume) {
            publishedVolume = volume
            val muted = isDeviceMuted
            listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
        }
    }
}

/**
 * The keyboard keys that step the app's headphone volume, mpv's defaults plus the `-`/`=` pair:
 * the TV's own volume keys never reach an app on TCL firmware. +1 up, -1 down, null for any other key.
 */
internal fun appVolumeKeyDirection(key: androidx.compose.ui.input.key.Key): Int? = when (key) {
    androidx.compose.ui.input.key.Key.Equals,
    androidx.compose.ui.input.key.Key.Plus,
    androidx.compose.ui.input.key.Key.NumPadAdd,
    androidx.compose.ui.input.key.Key.Multiply,
    androidx.compose.ui.input.key.Key.NumPadMultiply,
    -> 1
    androidx.compose.ui.input.key.Key.Minus,
    androidx.compose.ui.input.key.Key.NumPadSubtract,
    androidx.compose.ui.input.key.Key.Slash,
    androidx.compose.ui.input.key.Key.NumPadDivide,
    -> -1
    else -> null
}
