package tv.jellybeam.audio

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
import tv.jellybeam.player.forcePcmForRoute

class DspChainTest {

    private val crossfeed = AudioProfile("p1", "Synthetic", stages = listOf(DspStage.Crossfeed(0.35, 0.8, 0.5)))

    @Test
    fun `an empty profile at 0 dB is the identity`() {
        val chain = DspChain(AudioProfile("p", "Empty"), 48000)
        val io = doubleArrayOf(1234.0, -567.0)
        chain.process(io)
        assertEquals(1234.0, io[0], 1e-9)
        assertEquals(-567.0, io[1], 1e-9)
    }

    @Test
    fun `gain is applied after the stages`() {
        val chain = DspChain(AudioProfile("p", "Gain", gainDb = -6.0206), 48000)
        val io = doubleArrayOf(1000.0, 1000.0)
        chain.process(io)
        assertEquals(500.0, io[0], 0.01)
    }

    @Test
    fun `plans take stereo always and fold surround only on headphones`() {
        assertEquals(DspPlan.STEREO, dspPlan(C.ENCODING_PCM_16BIT, 2, headphones = false))
        assertEquals(DspPlan.DOWNMIX, dspPlan(C.ENCODING_PCM_16BIT, 8, headphones = true))
        assertEquals(DspPlan.BYPASS, dspPlan(C.ENCODING_PCM_16BIT, 6, headphones = false))
        assertEquals(DspPlan.DOWNMIX, dspPlan(C.ENCODING_PCM_16BIT, 1, headphones = true))
        assertEquals(DspPlan.BYPASS, dspPlan(C.ENCODING_PCM_16BIT, 1, headphones = false))
        assertEquals(DspPlan.BYPASS, dspPlan(C.ENCODING_PCM_FLOAT, 2, headphones = true))
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
        assertEquals(listOf(1.0, 1.0), checkNotNull(stereoDownmix(1))[0].toList())
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

    private fun stereo(frames: List<Pair<Int, Int>>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        frames.forEach { (l, r) -> b.putShort(l.toShort()).putShort(r.toShort()) }
        b.flip()
        return b
    }

    private fun run(p: ProfileAudioProcessor, frames: List<Pair<Int, Int>>): ShortArray {
        p.queueInput(stereo(frames))
        val out = p.output.order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(out.remaining() / 2) { out.short }
    }

    private fun configured(profile: () -> AudioProfile?, headphones: Boolean = true, channels: Int = 2): ProfileAudioProcessor =
        ProfileAudioProcessor(profile, { headphones }).apply {
            configure(AudioProcessor.AudioFormat(48000, channels, C.ENCODING_PCM_16BIT))
            flush()
        }

    /** A low tone in the left channel only, where crossfeed acts most. */
    private val leftTone = List(4800) { (10000 * sin(2 * PI * 100 * it / 48000)).toInt() to 0 }

    @Test
    fun `no profile passes stereo through bit-exact`() {
        val p = configured({ null })
        val frames = List(64) { (it * 300 - 9000) to (5000 - it * 100) }
        assertEquals(frames.flatMap { listOf(it.first.toShort(), it.second.toShort()) }, run(p, frames).toList())
    }

    @Test
    fun `a crossfeed profile carries hard-panned audio to the other ear`() {
        val out = run(configured({ crossfeed }), leftTone)
        val rightPeak = (2400 until 4800).maxOf { abs(out[it * 2 + 1].toInt()) }
        assertTrue("right ear peak $rightPeak", rightPeak > 2000)
    }

    @Test
    fun `swapping profiles mid-stream cross-fades instead of jumping`() {
        var active: AudioProfile? = null
        val p = configured({ active })
        run(p, leftTone.take(2400))
        active = crossfeed
        val out = run(p, leftTone.drop(2400))
        // The right ear starts silent and only reaches crossfeed level once the 20 ms (960 frame) fade is done.
        assertTrue(abs(out[1].toInt()) < 50)
        val early = (0 until 96).maxOf { abs(out[it * 2 + 1].toInt()) }
        val late = (1200 until 2400).maxOf { abs(out[it * 2 + 1].toInt()) }
        assertTrue("early $early late $late", early < late / 4)
    }

    @Test
    fun `an edited profile is picked up live`() {
        var active: AudioProfile? = crossfeed
        val p = configured({ active })
        run(p, leftTone.take(2400))
        active = crossfeed.copy(gainDb = -6.0)
        val out = run(p, leftTone.drop(2400))
        val before = run(configured({ crossfeed }), leftTone).let { o -> (3600 until 4800).maxOf { abs(o[it * 2].toInt()) } }
        val after = (1200 until 2400).maxOf { abs(out[it * 2].toInt()) }
        assertEquals(before / 2.0, after.toDouble(), before * 0.05)
    }

    @Test
    fun `volume scales the output and glides instead of jumping`() {
        var gain = 1.0
        val p = ProfileAudioProcessor({ null }, { true }, { gain }).apply {
            configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            flush()
        }
        val steady = List(4800) { 10000 to 10000 }
        run(p, steady.take(100))
        gain = 0.5
        val out = run(p, steady)
        assertTrue("first sample ${out[0]}", out[0] > 9900)
        assertEquals(5000, out[out.size - 2].toInt())
    }

    @Test
    fun `surround folds to stereo on headphones`() {
        val p = ProfileAudioProcessor({ crossfeed }, { true })
        assertEquals(2, p.configure(AudioProcessor.AudioFormat(48000, 8, C.ENCODING_PCM_16BIT)).channelCount)
        p.flush()
        val b = ByteBuffer.allocateDirect(8 * 2 * 10).order(ByteOrder.LITTLE_ENDIAN)
        repeat(10) { repeat(8) { ch -> b.putShort(if (ch == 2) 8000 else 0) } }
        b.flip()
        p.queueInput(b)
        assertEquals(10 * 4, p.output.remaining())
    }

    @Test
    fun `surround is left alone off headphones`() {
        val p = ProfileAudioProcessor({ crossfeed }, { false })
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            p.configure(AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)),
        )
        assertFalse(p.isActive)
    }
}
