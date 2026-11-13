package tv.jellybeam.audio

import kotlin.math.roundToInt
import tv.jellybeam.player.AudioOutputKind

/**
 * One processing step of an [AudioProfile]. Every stage works on stereo; new stage kinds (EQ, ...)
 * join this hierarchy and [AudioProfileCodec].
 */
sealed interface DspStage {
    /** FFmpeg `crossfeed` (see [tv.jellybeam.player.CrossfeedParams]); defaults are FFmpeg's own. */
    data class Crossfeed(
        val strength: Double = 0.2,
        val range: Double = 0.5,
        val slope: Double = 0.5,
    ) : DspStage
}

/** A user-made, named DSP chain; [gainDb] is applied after the last stage, for level matching. */
data class AudioProfile(
    val id: String,
    val name: String,
    val gainDb: Double = 0.0,
    val stages: List<DspStage> = emptyList(),
)

/** The outputs a profile can be the default for; [AudioOutputKind.UNKNOWN] never gets one. */
val PROFILE_OUTPUTS: List<AudioOutputKind> =
    listOf(AudioOutputKind.HEADPHONES, AudioOutputKind.SPEAKERS, AudioOutputKind.EXTERNAL)

/** Everything persisted: the user's profiles, and which one (if any) each output uses. */
data class AudioProfilesState(
    val profiles: List<AudioProfile> = emptyList(),
    val defaults: Map<AudioOutputKind, String> = emptyMap(),
) {
    fun profile(id: String?): AudioProfile? = id?.let { wanted -> profiles.firstOrNull { it.id == wanted } }

    /** The profile [output] plays through; null means unprocessed. */
    fun profileFor(output: AudioOutputKind): AudioProfile? = profile(defaults[output])
}

// -- Edits: pure, so the editor's rules are pinned by tests ---------------------------------------

/** Parameter ranges and D-pad steps; FFmpeg's own option bounds. */
object DspLimits {
    const val GAIN_MIN_DB = -20.0
    const val GAIN_MAX_DB = 12.0
    const val GAIN_STEP_DB = 0.5
    const val PARAM_STEP = 0.01
    const val SLOPE_MIN = 0.01
}

/** Rounds to the step grid so repeated float steps never drift: dividing by the inverse keeps 0.35 exact. */
internal fun snap(value: Double, step: Double): Double {
    val perUnit = (1.0 / step).roundToInt().toDouble()
    return (value * perUnit).roundToInt() / perUnit
}

internal fun stepClamped(value: Double, delta: Double, step: Double, min: Double, max: Double): Double =
    snap(value + delta, step).coerceIn(min, max)

fun AudioProfilesState.updateProfile(id: String, change: (AudioProfile) -> AudioProfile): AudioProfilesState =
    copy(profiles = profiles.map { if (it.id == id) change(it) else it })

/** Appends a profile named [name]; [newId] is injected so tests stay deterministic. */
fun AudioProfilesState.addProfile(newId: String, name: String): AudioProfilesState =
    copy(profiles = profiles + AudioProfile(id = newId, name = name))

fun AudioProfilesState.duplicateProfile(id: String, newId: String, name: String): AudioProfilesState {
    val source = profile(id) ?: return this
    val index = profiles.indexOf(source)
    val copy = source.copy(id = newId, name = name)
    return copy(profiles = profiles.toMutableList().apply { add(index + 1, copy) })
}

/** Removing a profile also clears it as any output's default, so no output points at nothing. */
fun AudioProfilesState.deleteProfile(id: String): AudioProfilesState =
    copy(profiles = profiles.filterNot { it.id == id }, defaults = defaults.filterValues { it != id })

fun AudioProfilesState.setDefault(output: AudioOutputKind, id: String?): AudioProfilesState =
    copy(defaults = if (id == null || profile(id) == null) defaults - output else defaults + (output to id))

fun AudioProfile.stepGain(direction: Int): AudioProfile = copy(
    gainDb = stepClamped(gainDb, direction * DspLimits.GAIN_STEP_DB, DspLimits.GAIN_STEP_DB, DspLimits.GAIN_MIN_DB, DspLimits.GAIN_MAX_DB),
)

fun AudioProfile.addStage(stage: DspStage): AudioProfile = copy(stages = stages + stage)

fun AudioProfile.removeStage(index: Int): AudioProfile =
    if (index in stages.indices) copy(stages = stages.toMutableList().apply { removeAt(index) }) else this

/** Moves stage [index] by [delta] places; out-of-range moves are no-ops. */
fun AudioProfile.moveStage(index: Int, delta: Int): AudioProfile {
    val target = index + delta
    if (index !in stages.indices || target !in stages.indices) return this
    return copy(stages = stages.toMutableList().apply { add(target, removeAt(index)) })
}

fun AudioProfile.replaceStage(index: Int, stage: DspStage): AudioProfile =
    if (index in stages.indices) copy(stages = stages.toMutableList().apply { set(index, stage) }) else this

/** The crossfeed parameters the editor steps. */
enum class CrossfeedParam { STRENGTH, RANGE, SLOPE }

fun DspStage.Crossfeed.step(param: CrossfeedParam, direction: Int): DspStage.Crossfeed {
    val d = direction * DspLimits.PARAM_STEP
    return when (param) {
        CrossfeedParam.STRENGTH -> copy(strength = stepClamped(strength, d, DspLimits.PARAM_STEP, 0.0, 1.0))
        CrossfeedParam.RANGE -> copy(range = stepClamped(range, d, DspLimits.PARAM_STEP, 0.0, 1.0))
        CrossfeedParam.SLOPE -> copy(slope = stepClamped(slope, d, DspLimits.PARAM_STEP, DspLimits.SLOPE_MIN, 1.0))
    }
}

/** FFmpeg's shelf corner for [DspStage.Crossfeed.range]: `(1 - range) * 2100` Hz. */
fun DspStage.Crossfeed.cutoffHz(): Int = ((1.0 - range) * 2100).roundToInt()

/** FFmpeg's side cut below the corner, in dB (negative). */
fun DspStage.Crossfeed.sideCutDb(): Double = -strength * 30

/** "Profile N" with the lowest N not already a profile's name. */
fun nextProfileName(existing: List<AudioProfile>, base: String): String {
    val names = existing.map { it.name }.toSet()
    return generateSequence(1) { it + 1 }.map { "$base $it" }.first { it !in names }
}
