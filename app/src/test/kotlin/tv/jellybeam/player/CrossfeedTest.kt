package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val filter = CrossfeedFilter(CrossfeedParams(), 48000)
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

    @Test
    fun `plans take stereo always and fold surround only on headphones`() {
        assertEquals(CrossfeedPlan.STEREO, crossfeedPlan(C.ENCODING_PCM_16BIT, 2, headphones = false))
        assertEquals(CrossfeedPlan.DOWNMIX, crossfeedPlan(C.ENCODING_PCM_16BIT, 8, headphones = true))
        assertEquals(CrossfeedPlan.BYPASS, crossfeedPlan(C.ENCODING_PCM_16BIT, 6, headphones = false))
        assertEquals(CrossfeedPlan.BYPASS, crossfeedPlan(C.ENCODING_PCM_16BIT, 1, headphones = true))
        assertEquals(CrossfeedPlan.BYPASS, crossfeedPlan(C.ENCODING_PCM_FLOAT, 2, headphones = true))
    }

    @Test
    fun `downmix rows never exceed unity and drop LFE`() {
        for (channels in 3..8) {
            val m = checkNotNull(stereoDownmix(channels))
            assertEquals(channels, m.size)
            assertTrue(m.sumOf { it[0] } <= 1.0 + 1e-12)
            assertTrue(m.sumOf { it[1] } <= 1.0 + 1e-12)
        }
        val m51 = checkNotNull(stereoDownmix(6))
        assertEquals(0.0, m51[3][0], 0.0)
        assertEquals(0.0, m51[3][1], 0.0)
        assertNull(stereoDownmix(2))
        assertNull(stereoDownmix(9))
    }

    @Test
    fun `headphones force decoding while other routes keep passthrough`() {
        assertTrue(forcePcmForRoute(MimeTypes.AUDIO_TRUEHD, headphones = true))
        assertTrue(forcePcmForRoute(MimeTypes.AUDIO_E_AC3, headphones = true))
        assertFalse(forcePcmForRoute(MimeTypes.AUDIO_RAW, headphones = true))
        assertFalse(forcePcmForRoute(MimeTypes.AUDIO_TRUEHD, headphones = false))
    }

    private fun pcm(frames: List<Pair<Int, Int>>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        frames.forEach { (l, r) -> b.putShort(l.toShort()).putShort(r.toShort()) }
        b.flip()
        return b
    }

    private fun run(p: CrossfeedAudioProcessor, frames: List<Pair<Int, Int>>): ShortArray {
        p.queueInput(pcm(frames))
        val out = p.output.order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun `off headphones stereo passes through bit-exact`() {
        val p = CrossfeedAudioProcessor(headphones = { false })
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val frames = List(64) { (it * 300 - 9000) to (5000 - it * 100) }
        val out = run(p, frames)
        assertEquals(frames.flatMap { listOf(it.first.toShort(), it.second.toShort()) }, out.toList())
    }

    @Test
    fun `on headphones hard-panned audio reaches the other ear`() {
        val p = CrossfeedAudioProcessor(headphones = { true })
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        // A low tone in the left channel only, where crossfeed acts most.
        val frames = List(4800) { (10000 * sin(2 * PI * 100 * it / 48000)).toInt() to 0 }
        val out = run(p, frames)
        val rightPeak = (2400 until 4800).maxOf { abs(out[it * 2 + 1].toInt()) }
        assertTrue("right ear peak $rightPeak", rightPeak > 2000)
    }

    @Test
    fun `surround folds to stereo on headphones`() {
        val p = CrossfeedAudioProcessor(headphones = { true })
        val outFormat = p.configure(AudioProcessor.AudioFormat(48000, 8, C.ENCODING_PCM_16BIT))
        assertEquals(2, outFormat.channelCount)
        p.flush()
        val b = ByteBuffer.allocateDirect(8 * 2 * 10).order(ByteOrder.LITTLE_ENDIAN)
        repeat(10) { repeat(8) { ch -> b.putShort(if (ch == 2) 8000 else 0) } }
        b.flip()
        p.queueInput(b)
        assertEquals(10 * 4, p.output.remaining())
    }

    @Test
    fun `surround is left alone off headphones`() {
        val p = CrossfeedAudioProcessor(headphones = { false })
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            p.configure(AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)),
        )
        assertFalse(p.isActive)
    }
}
