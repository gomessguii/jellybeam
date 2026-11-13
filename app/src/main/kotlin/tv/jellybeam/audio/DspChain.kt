package tv.jellybeam.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt
import tv.jellybeam.player.CrossfeedFilter
import tv.jellybeam.player.CrossfeedParams

/** One stereo frame in, one out, in place in `io` (`io[0]` left, `io[1]` right). */
fun interface StereoStage {
    fun process(io: DoubleArray)
}

/** [profile]'s stages then its gain, built for [sampleRate]; pure and JVM-tested. */
class DspChain(val profile: AudioProfile, sampleRate: Int) {
    private val stages: List<StereoStage> = profile.stages.map { stage ->
        when (stage) {
            is DspStage.Crossfeed -> {
                val filter = CrossfeedFilter(CrossfeedParams(stage.strength, stage.range, stage.slope), sampleRate)
                val out = DoubleArray(2)
                StereoStage { io ->
                    filter.process(io[0], io[1], out)
                    io[0] = out[0]
                    io[1] = out[1]
                }
            }
        }
    }
    private val gain = 10.0.pow(profile.gainDb / 20)

    fun process(io: DoubleArray) {
        for (stage in stages) stage.process(io)
        io[0] *= gain
        io[1] *= gain
    }
}

/** What the processor does with a stream: leave it alone, process stereo, or fold surround to stereo first. */
enum class DspPlan { BYPASS, STEREO, DOWNMIX }

/**
 * Stereo is always taken so a profile can come and go live; mono and surround are folded to stereo
 * only when headphones are the route at configure time (so the headphone volume reaches them too),
 * since speakers' and receivers' own processing want the channels. Profiles run on stereo only.
 */
fun dspPlan(encoding: Int, channelCount: Int, headphones: Boolean): DspPlan = when {
    encoding != C.ENCODING_PCM_16BIT -> DspPlan.BYPASS
    channelCount == 2 -> DspPlan.STEREO
    (channelCount == 1 || channelCount in 3..8) && headphones -> DspPlan.DOWNMIX
    else -> DspPlan.BYPASS
}

private const val MINUS_3DB = 0.7071067811865476

/**
 * Left/right coefficients per input channel in Android's canonical order for [channelCount],
 * libswresample's default fold (centre and surrounds at -3 dB, LFE dropped) normalised so a row
 * never exceeds unity; null where the layout is not a known one.
 */
fun stereoDownmix(channelCount: Int): Array<DoubleArray>? {
    val fl = doubleArrayOf(1.0, 0.0)
    val fr = doubleArrayOf(0.0, 1.0)
    val c = doubleArrayOf(MINUS_3DB, MINUS_3DB)
    val lfe = doubleArrayOf(0.0, 0.0)
    val sl = doubleArrayOf(MINUS_3DB, 0.0)
    val sr = doubleArrayOf(0.0, MINUS_3DB)
    val bc = doubleArrayOf(MINUS_3DB * MINUS_3DB, MINUS_3DB * MINUS_3DB)
    val rows = when (channelCount) {
        1 -> arrayOf(doubleArrayOf(1.0, 1.0))
        3 -> arrayOf(fl, fr, c)
        4 -> arrayOf(fl, fr, sl, sr)
        5 -> arrayOf(fl, fr, c, sl, sr)
        6 -> arrayOf(fl, fr, c, lfe, sl, sr)
        7 -> arrayOf(fl, fr, c, lfe, sl, sr, bc)
        8 -> arrayOf(fl, fr, c, lfe, sl, sr, sl, sr)
        else -> return null
    }
    val norm = maxOf(rows.sumOf { it[0] }, rows.sumOf { it[1] }, 1.0)
    return Array(rows.size) { i -> doubleArrayOf(rows[i][0] / norm, rows[i][1] / norm) }
}

/** Profile swap cross-fade, so a switch, an edit or a route change never clicks. */
private const val FADE_MS = 20

/** Volume glide time: a remote press moves the level over this long, never as a zipper-noise jump. */
private const val VOLUME_GLIDE_MS = 30

/**
 * The sink's PCM stage for audio profiles. [profile] is read every buffer; when it changes, the old
 * and new chains both run for [FADE_MS] and cross-fade, so swaps are live and seamless. Passthrough
 * never reaches a processor, so this only ever sees PCM.
 */
@UnstableApi
class ProfileAudioProcessor(
    private val profile: () -> AudioProfile?,
    private val headphones: () -> Boolean,
    /** Linear gain after the profile (the app's headphone volume); read every buffer, glided per sample. */
    private val volume: () -> Double = { 1.0 },
) : BaseAudioProcessor() {

    private var gain = 1.0
    private var gainStep = 1.0

    private var plan = DspPlan.BYPASS
    private var sampleRate = 48000
    private var inChannels = 2
    private var downmix: Array<DoubleArray>? = null
    private var current: DspChain? = null
    private var fadingOut: DspChain? = null
    private var fadePos = 0
    private var fadeLen = 1
    private val dry = DoubleArray(2)
    private val wetNew = DoubleArray(2)
    private val wetOld = DoubleArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        plan = dspPlan(inputAudioFormat.encoding, inputAudioFormat.channelCount, headphones())
        if (plan == DspPlan.BYPASS) return AudioProcessor.AudioFormat.NOT_SET
        inChannels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        downmix = if (plan == DspPlan.DOWNMIX) stereoDownmix(inChannels) else null
        fadeLen = (sampleRate * FADE_MS / 1000).coerceAtLeast(1)
        gainStep = 1.0 / (sampleRate * VOLUME_GLIDE_MS / 1000).coerceAtLeast(1)
        return AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (inChannels * 2)
        if (frames == 0) return
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        follow(profile())
        val targetGain = volume()
        val matrix = downmix
        repeat(frames) {
            if (matrix == null) {
                dry[0] = input.short.toDouble()
                dry[1] = input.short.toDouble()
            } else {
                var l = 0.0
                var r = 0.0
                for (ch in 0 until inChannels) {
                    val s = input.short.toDouble()
                    l += s * matrix[ch][0]
                    r += s * matrix[ch][1]
                }
                dry[0] = l
                dry[1] = r
            }
            render(current, wetNew)
            if (fadePos < fadeLen) {
                render(fadingOut, wetOld)
                val t = fadePos.toDouble() / fadeLen
                wetNew[0] = wetOld[0] + (wetNew[0] - wetOld[0]) * t
                wetNew[1] = wetOld[1] + (wetNew[1] - wetOld[1]) * t
                if (++fadePos == fadeLen) fadingOut = null
            }
            if (gain != targetGain) {
                gain = if (gain < targetGain) minOf(targetGain, gain + gainStep) else maxOf(targetGain, gain - gainStep)
            }
            output.putShort(toPcm16(wetNew[0] * gain))
            output.putShort(toPcm16(wetNew[1] * gain))
        }
        output.flip()
    }

    /** Starts a cross-fade when the wanted profile differs from the one playing. */
    private fun follow(wanted: AudioProfile?) {
        if (wanted == current?.profile) return
        fadingOut = current
        current = wanted?.let { DspChain(it, sampleRate) }
        fadePos = 0
        log("profile ${wanted?.name ?: "none"} (${plan.name.lowercase()}, ${inChannels}ch)")
    }

    /** [chain]'s output for the current dry frame into [out]; no chain is the dry signal. */
    private fun render(chain: DspChain?, out: DoubleArray) {
        out[0] = dry[0]
        out[1] = dry[1]
        chain?.process(out)
    }

    override fun onFlush() {
        // A seek starts clean at the wanted profile: no stale filter state, no fade.
        current = profile()?.let { DspChain(it, sampleRate) }
        fadingOut = null
        fadePos = fadeLen
        gain = volume()
    }

    override fun onReset() {
        plan = DspPlan.BYPASS
        current = null
        fadingOut = null
        downmix = null
    }

    /** One line per profile change, so a route change or swap can be checked over adb. */
    private fun log(message: String) {
        android.util.Log.i("JellybeamDsp", message)
    }

    private fun toPcm16(v: Double): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()
}
