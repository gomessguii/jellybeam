package tv.jellybeam.player

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfeedTest {

    /** The synthetic signal the FFmpeg reference was rendered from. */
    private fun input(i: Int): Pair<Double, Double> {
        val fs = 48000.0
        val l = 0.5 * sin(2 * PI * 200 * i / fs) + 0.2 * sin(2 * PI * 5000 * i / fs)
        val r = 0.3 * sin(2 * PI * 60 * i / fs + 1.0)
        return l to r
    }

    @Test
    fun `the filter matches FFmpeg crossfeed sample for sample`() {
        // `ffmpeg -f f64le -ar 48000 -ac 2 -i <signal> -af crossfeed=strength=0.35:range=0.8:slope=0.5`
        val reference = mapOf(
            0 to (0.0038178716280447417 to 0.2233792942700873),
            1 to (0.13042428585953822 to 0.2192682965082753),
            2 to (0.20795269222362628 to 0.2189256829012286),
            100 to (0.2926992555769591 to 0.2861072856758996),
            1000 to (0.497241781405649 to 0.19423584556295184),
            4799 to (0.003829893909678257 to 0.10085784092734229),
        )
        val filter = CrossfeedFilter(CrossfeedParams(strength = 0.35, range = 0.8, slope = 0.5), 48000)
        val out = DoubleArray(2)
        for (i in 0 until 4800) {
            val (l, r) = input(i)
            filter.process(l, r, out)
            reference[i]?.let { (el, er) ->
                assertEquals("L[$i]", el, out[0], 1e-12)
                assertEquals("R[$i]", er, out[1], 1e-12)
            }
        }
    }

    @Test
    fun `mono content passes at level_in`() {
        val filter = CrossfeedFilter(CrossfeedParams(), 48000)
        val out = DoubleArray(2)
        repeat(100) { filter.process(1000.0, 1000.0, out) }
        assertEquals(900.0, out[0], 1e-9)
        assertEquals(900.0, out[1], 1e-9)
    }
}
