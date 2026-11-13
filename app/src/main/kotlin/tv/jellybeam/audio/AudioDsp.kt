package tv.jellybeam.audio

import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.jellybeam.player.AudioOutputKind
import tv.jellybeam.player.AudioRoute
import tv.jellybeam.player.AudioRouteHub

/**
 * Owns the user's audio profiles: app-side JSON in [file] (the Rust settings record can't grow
 * without a core rebuild), and the profile the current route plays through. [route] is read on
 * the audio thread, so [activeProfile] is a cheap volatile read, never a suspend.
 */
class AudioDsp(
    private val file: File,
    private val route: StateFlow<AudioRoute>,
    private val scope: CoroutineScope,
    hub: AudioRouteHub? = null,
) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        // Whether the app's volume applies follows the route, so route flips are changes too.
        hub?.addHeadphonesListener { notifyListeners() }
    }

    /** [listener] runs on an arbitrary thread after any profile, volume or headphone-route change. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() = listeners.forEach { it() }

    /** The app's volume steers this route: headphones, whose hardware volume the TV may ignore. */
    val ownsVolume: Boolean get() = route.value.kind == AudioOutputKind.HEADPHONES

    /** Linear gain the audio thread applies after the profile; unity off headphones. */
    val volumeGain: Double get() = if (ownsVolume) HeadphoneVolume.gain(_state.value.headphoneVolume) else 1.0

    val headphoneVolume: Int get() = _state.value.headphoneVolume

    fun setHeadphoneVolume(steps: Int) {
        val target = HeadphoneVolume.clamp(steps)
        if (target != _state.value.headphoneVolume) update { it.copy(headphoneVolume = target) }
    }

    fun stepHeadphoneVolume(delta: Int) = setHeadphoneVolume(_state.value.headphoneVolume + delta)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AudioProfilesState> = _state.asStateFlow()

    private val writeLock = Mutex()

    /** The profile the audio thread applies now; null plays unprocessed. */
    val activeProfile: AudioProfile? get() = _state.value.profileFor(route.value.kind)

    val currentOutput: AudioOutputKind get() = route.value.kind

    /** Applies [change] at once (the audio thread hears it on its next buffer), then saves in order. */
    fun update(change: (AudioProfilesState) -> AudioProfilesState) {
        val next = synchronized(this) {
            change(_state.value).also { _state.value = it }
        }
        notifyListeners()
        scope.launch(Dispatchers.IO) { writeLock.withLock { save(next) } }
    }

    /** The player's profile switcher: [id] (or none) becomes the current output's default. */
    fun selectForCurrentOutput(id: String?) {
        val output = currentOutput
        if (output !in PROFILE_OUTPUTS) return
        update { it.setDefault(output, id) }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun load(): AudioProfilesState =
        runCatching { if (file.exists()) AudioProfileCodec.decode(file.readText()) else null }
            .onFailure { Log.w(TAG, "profiles unreadable; starting empty", it) }
            .getOrNull() ?: AudioProfilesState()

    /** Only the latest state is ever written; a stale save landing after a newer one is skipped. */
    private fun save(snapshot: AudioProfilesState) {
        if (snapshot != _state.value) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AudioProfileCodec.encode(snapshot))
            if (!tmp.renameTo(file)) error("rename failed")
        }.onFailure { Log.w(TAG, "profiles not saved", it) }
    }

    private companion object {
        const val TAG = "JellybeamAudioDsp"
    }
}
