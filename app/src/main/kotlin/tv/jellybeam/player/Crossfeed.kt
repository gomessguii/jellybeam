package tv.jellybeam.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFmpeg `crossfeed` options with its meanings: [strength] cuts the side (L-R) signal by
 * `strength * 30` dB below `(1 - range) * 2100` Hz, [slope] shapes the shelf, [levelIn] and
 * [levelOut] are FFmpeg's fixed defaults. Defaults are FFmpeg's own.
 */
data class CrossfeedParams(
    val strength: Double = 0.2,
    val range: Double = 0.5,
    val slope: Double = 0.5,
    val levelIn: Double = 0.9,
    val levelOut: Double = 1.0,
)

/**
 * A line-for-line port of FFmpeg's af_crossfeed (per-sample path): an RBJ low shelf on the side
 * signal, mid untouched. Pure and JVM-tested against FFmpeg's own output; one stage of a
 * [tv.jellybeam.audio.DspChain].
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
