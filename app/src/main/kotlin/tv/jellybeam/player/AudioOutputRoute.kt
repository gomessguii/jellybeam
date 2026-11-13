package tv.jellybeam.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who is listening at the end of the media route; headphone-only processing keys off [HEADPHONES]. */
enum class AudioOutputKind { HEADPHONES, SPEAKERS, EXTERNAL, UNKNOWN }

/** Where an [AudioRoute] came from, most trusted first: the live track's routed device, the platform's media route, a prediction. */
enum class RouteSource { TRACK, PLATFORM, PREDICTED, NONE }

/** The media output in effect; [deviceType] is an [AudioDeviceInfo] `TYPE_*`, null when nothing is known. */
data class AudioRoute(val kind: AudioOutputKind, val deviceType: Int?, val source: RouteSource) {
    companion object {
        val NONE = AudioRoute(AudioOutputKind.UNKNOWN, null, RouteSource.NONE)
    }
}

/**
 * Bluetooth A2DP and USB audio count as headphones: the platform can't tell a BT speaker or a USB DAC
 * feeding speakers from a headset, and headphones are what those routes almost always are on a TV.
 */
fun classifyAudioDevice(type: Int): AudioOutputKind = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> AudioOutputKind.HEADPHONES

    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    -> AudioOutputKind.SPEAKERS

    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE,
    AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    -> AudioOutputKind.EXTERNAL

    else -> AudioOutputKind.UNKNOWN
}

/**
 * AOSP audio policy's media-strategy order (Engine::getDevicesForStrategyInt, STRATEGY_MEDIA): below
 * API 33 nothing public names the media route before a track exists, so the first connected entry wins.
 */
internal val MEDIA_ROUTE_PRIORITY: List<Int> = listOf(
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_HDMI_EARC,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
)

/** The output the platform would pick for media among [connected] types; null when none is connected. */
fun predictMediaRoute(connected: Collection<Int>): Int? =
    MEDIA_ROUTE_PRIORITY.firstOrNull { it in connected } ?: connected.firstOrNull()

/** Pure: the most trusted source that knows a device wins (see [RouteSource]). */
fun resolveAudioRoute(trackDevice: Int?, platformDevice: Int?, connected: Collection<Int>): AudioRoute {
    val (type, source) = when {
        trackDevice != null -> trackDevice to RouteSource.TRACK
        platformDevice != null -> platformDevice to RouteSource.PLATFORM
        else -> predictMediaRoute(connected)?.let { it to RouteSource.PREDICTED } ?: return AudioRoute.NONE
    }
    return AudioRoute(classifyAudioDevice(type), type, source)
}

/** Receives the device each live audio track is routed to; implemented by [AudioOutputMonitor]. */
interface AudioRouteSink {
    fun onTrackRouted(outputId: Int, deviceType: Int?)
    fun onTrackReleased(outputId: Int)
}

/** The route as the audio pipeline needs it: whether headphones are listening, and when that flips. */
interface AudioRouteHub : AudioRouteSink {
    val isHeadphones: Boolean

    /** [listener] runs on an arbitrary thread each time [isHeadphones] changes. */
    fun addHeadphonesListener(listener: (Boolean) -> Unit)
}

/**
 * Headphones get decoded PCM only: crossfeed can't touch a bitstream, and a TV HAL may claim
 * passthrough on a USB or Bluetooth route it can't actually open.
 */
fun forcePcmForRoute(sampleMimeType: String?, headphones: Boolean): Boolean =
    headphones && sampleMimeType != MimeTypes.AUDIO_RAW

/**
 * The current media [AudioRoute] as a [StateFlow]: device connect/disconnect events re-resolve it, and
 * each live [AudioTrack] reports its real routed device through [AudioRouteSink], which beats both
 * the platform answer and the prediction while that track lives.
 */
class AudioOutputMonitor(context: Context) : AudioRouteHub {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Live tracks' routed device types in routing order; the latest routed one speaks for the route. */
    private val trackDevices = LinkedHashMap<Int, Int?>()

    private val _route = MutableStateFlow(AudioRoute.NONE)
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    override val isHeadphones: Boolean get() = _route.value.kind == AudioOutputKind.HEADPHONES

    private val headphonesListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    override fun addHeadphonesListener(listener: (Boolean) -> Unit) {
        headphonesListeners += listener
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    /** Idempotent; the registration fires once immediately with the current devices. */
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refresh()
    }

    override fun onTrackRouted(outputId: Int, deviceType: Int?) {
        synchronized(lock) {
            trackDevices.remove(outputId)
            trackDevices[outputId] = deviceType
        }
        refresh()
    }

    override fun onTrackReleased(outputId: Int) {
        synchronized(lock) { trackDevices.remove(outputId) }
        refresh()
    }

    /** Whole read-resolve-publish under [lock], so a slower concurrent refresh can't publish a stale route. */
    fun refresh() {
        val (prev, next) = synchronized(lock) {
            val track = trackDevices.values.lastOrNull { it != null }
            val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            val platform = if (Build.VERSION.SDK_INT >= 33) platformMediaDevice() else null
            val next = resolveAudioRoute(track, platform, connected)
            (_route.value to next).also { _route.value = next }
        }
        if (prev != next) Log.i(TAG, "route ${next.kind} type=${next.deviceType} via ${next.source}")
        val headphones = next.kind == AudioOutputKind.HEADPHONES
        if (headphones != (prev.kind == AudioOutputKind.HEADPHONES)) headphonesListeners.forEach { it(headphones) }
    }

    @RequiresApi(33)
    private fun platformMediaDevice(): Int? =
        audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()?.type

    private companion object {
        const val TAG = "JellybeamAudioRoute"
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    }
}

/** Reports [track]'s routed device to [sink] now and on every reroute until [release]; API 24 for [AudioRouting]. */
@RequiresApi(24)
internal class AudioTrackRouteWatch(
    private val track: AudioTrack,
    private val outputId: Int,
    private val sink: AudioRouteSink,
) {
    private val listener = AudioRouting.OnRoutingChangedListener { router ->
        sink.onTrackRouted(outputId, router.routedDevice?.type)
    }

    init {
        track.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
        track.routedDevice?.let { sink.onTrackRouted(outputId, it.type) }
    }

    fun release() {
        track.removeOnRoutingChangedListener(listener)
        sink.onTrackReleased(outputId)
    }
}
