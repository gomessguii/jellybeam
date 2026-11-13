package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFmpeg `crossfeed` options with its meanings: [strength] cuts the side (L-R) signal by
 * `strength * 30` dB below `(1 - range) * 2100` Hz, [slope] shapes the shelf. Defaults are the
 * tuned headphone preset (FFmpeg `crossfeed=strength=0.35:range=0.8:slope=0.5`).
 */
data class CrossfeedParams(
    val strength: Double = 0.35,
    val range: Double = 0.8,
    val slope: Double = 0.5,
    val levelIn: Double = 0.9,
    val levelOut: Double = 1.0,
)

/**
 * A line-for-line port of FFmpeg's af_crossfeed (per-sample path): an RBJ low shelf on the side
 * signal, mid untouched. Pure and JVM-tested against FFmpeg's own output.
 */
class CrossfeedFilter(params: CrossfeedParams, sampleRate: Int) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private val levelIn = params.levelIn
    private val levelOut = params.levelOut
    private var w1 = 0.0
    private var w2 = 0.0

    init {
        val a = 10.0.pow(params.strength * -30 / 40)
        val w0 = 2 * PI * (1.0 - params.range) * 2100 / sampleRate
        val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / params.slope - 1) + 2)
        val sa = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cos(w0) + sa
        a1 = -(-2 * ((a - 1) + (a + 1) * cos(w0))) / a0
        a2 = -((a + 1) + (a - 1) * cos(w0) - sa) / a0
        b0 = a * ((a + 1) - (a - 1) * cos(w0) + sa) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cos(w0)) / a0
        b2 = a * ((a + 1) - (a - 1) * cos(w0) - sa) / a0
    }

    /** Filters one stereo frame in place in [out] (`out[0]` left, `out[1]` right). */
    fun process(left: Double, right: Double, out: DoubleArray) {
        val mid = (left + right) * levelIn * .5
        val side = (left - right) * levelIn * .5
        val oside = side * b0 + w1
        w1 = b1 * side + w2 + a1 * oside
        w2 = b2 * side + a2 * oside
        out[0] = (mid + oside) * levelOut
        out[1] = (mid - oside) * levelOut
    }

    fun reset() {
        w1 = 0.0
        w2 = 0.0
    }
}

/** What the processor does with a stream: leave it alone, crossfeed stereo, or fold surround to stereo first. */
enum class CrossfeedPlan { BYPASS, STEREO, DOWNMIX }

/**
 * Stereo is always taken so crossfeed can follow the route live; surround is folded only when
 * headphones are the route at configure time, since a receiver must keep its channels.
 */
fun crossfeedPlan(encoding: Int, channelCount: Int, headphones: Boolean): CrossfeedPlan = when {
    encoding != C.ENCODING_PCM_16BIT -> CrossfeedPlan.BYPASS
    channelCount == 2 -> CrossfeedPlan.STEREO
    channelCount in 3..8 && headphones -> CrossfeedPlan.DOWNMIX
    else -> CrossfeedPlan.BYPASS
}

private const val MINUS_3DB = 0.7071067811865476

/**
 * Left/right coefficients per input channel in Android's canonical order for [channelCount],
 * libswresample's default fold (centre and surrounds at -3 dB, LFE dropped) normalised so a row
 * never exceeds unity; null where the layout is not a known one.
 */
fun stereoDownmix(channelCount: Int): Array<DoubleArray>? {
    // L, R, C, LFE, side/back pairs and a back centre, as Android orders them per channel count.
    val fl = doubleArrayOf(1.0, 0.0)
    val fr = doubleArrayOf(0.0, 1.0)
    val c = doubleArrayOf(MINUS_3DB, MINUS_3DB)
    val lfe = doubleArrayOf(0.0, 0.0)
    val sl = doubleArrayOf(MINUS_3DB, 0.0)
    val sr = doubleArrayOf(0.0, MINUS_3DB)
    val bc = doubleArrayOf(MINUS_3DB * MINUS_3DB, MINUS_3DB * MINUS_3DB)
    val rows = when (channelCount) {
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

/** Crossfeed on/off ramp, so a route change never clicks. */
private const val RAMP_MS = 20

/**
 * Headphone crossfeed in the sink's PCM chain; [headphones] is read every buffer so it follows the
 * route live, fading over [RAMP_MS]. Passthrough never reaches a processor, so this only sees PCM.
 */
@UnstableApi
class CrossfeedAudioProcessor(
    private val headphones: () -> Boolean,
    private val params: CrossfeedParams = CrossfeedParams(),
) : BaseAudioProcessor() {

    private var plan = CrossfeedPlan.BYPASS
    private var filter: CrossfeedFilter? = null
    private var downmix: Array<DoubleArray>? = null
    private var inChannels = 2
    private var mix = 0.0
    private var rampStep = 1.0
    private val wet = DoubleArray(2)
    private var lastTarget = -1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val onHeadphones = headphones()
        plan = crossfeedPlan(inputAudioFormat.encoding, inputAudioFormat.channelCount, onHeadphones)
        if (plan == CrossfeedPlan.BYPASS) return AudioProcessor.AudioFormat.NOT_SET
        inChannels = inputAudioFormat.channelCount
        downmix = if (plan == CrossfeedPlan.DOWNMIX) stereoDownmix(inChannels) else null
        filter = CrossfeedFilter(params, inputAudioFormat.sampleRate)
        rampStep = 1.0 / (inputAudioFormat.sampleRate * RAMP_MS / 1000.0).coerceAtLeast(1.0)
        mix = if (onHeadphones) 1.0 else 0.0
        lastTarget = -1.0
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFrameBytes = inChannels * 2
        val frames = inputBuffer.remaining() / inFrameBytes
        if (frames == 0) return
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(frames * 4)
        val f = checkNotNull(filter)
        val matrix = downmix
        val target = if (headphones()) 1.0 else 0.0
        if (target != lastTarget) {
            lastTarget = target
            log("crossfeed ${if (target > 0) "on" else "off"} (${plan.name.lowercase()}, ${inChannels}ch)")
        }
        repeat(frames) {
            var l: Double
            var r: Double
            if (matrix == null) {
                l = input.short.toDouble()
                r = input.short.toDouble()
            } else {
                l = 0.0
                r = 0.0
                for (ch in 0 until inChannels) {
                    val s = input.short.toDouble()
                    l += s * matrix[ch][0]
                    r += s * matrix[ch][1]
                }
            }
            // The filter always runs so its state is warm when the ramp brings it in.
            f.process(l, r, wet)
            if (mix != target) mix = if (mix < target) minOf(target, mix + rampStep) else maxOf(target, mix - rampStep)
            output.putShort(toPcm16(l + (wet[0] - l) * mix))
            output.putShort(toPcm16(r + (wet[1] - r) * mix))
        }
        output.flip()
    }

    override fun onFlush() {
        filter?.reset()
        mix = if (headphones()) 1.0 else 0.0
    }

    override fun onReset() {
        plan = CrossfeedPlan.BYPASS
        filter = null
        downmix = null
    }

    /** One line per on/off flip, so a route change can be checked over adb. */
    private fun log(message: String) {
        android.util.Log.i("JellybeamCrossfeed", message)
    }

    private fun toPcm16(v: Double): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()
}
