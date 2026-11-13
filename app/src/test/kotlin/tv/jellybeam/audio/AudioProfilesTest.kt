package tv.jellybeam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.player.AudioOutputKind

class AudioProfilesTest {

    private val a = AudioProfile("a", "Alpha", gainDb = -1.0, stages = listOf(DspStage.Crossfeed(0.35, 0.8, 0.5)))
    private val b = AudioProfile("b", "Beta")
    private val state = AudioProfilesState(listOf(a, b), mapOf(AudioOutputKind.HEADPHONES to "a"))

    @Test
    fun `each output resolves to its own default, or none`() {
        assertEquals(a, state.profileFor(AudioOutputKind.HEADPHONES))
        assertNull(state.profileFor(AudioOutputKind.SPEAKERS))
        assertNull(state.profileFor(AudioOutputKind.UNKNOWN))
    }

    @Test
    fun `deleting a profile clears it as a default`() {
        val after = state.deleteProfile("a")
        assertEquals(listOf(b), after.profiles)
        assertNull(after.profileFor(AudioOutputKind.HEADPHONES))
    }

    @Test
    fun `a default can only point at an existing profile`() {
        assertEquals(state.defaults - AudioOutputKind.HEADPHONES, state.setDefault(AudioOutputKind.HEADPHONES, "missing").defaults)
        assertEquals("b", state.setDefault(AudioOutputKind.SPEAKERS, "b").defaults[AudioOutputKind.SPEAKERS])
        assertNull(state.setDefault(AudioOutputKind.HEADPHONES, null).defaults[AudioOutputKind.HEADPHONES])
    }

    @Test
    fun `duplicates land right after their source with a new id`() {
        val after = state.duplicateProfile("a", "c", "Alpha copy")
        assertEquals(listOf("a", "c", "b"), after.profiles.map { it.id })
        assertEquals(a.stages, after.profile("c")?.stages)
    }

    @Test
    fun `steps snap to the grid and clamp to FFmpeg's bounds`() {
        var cf = DspStage.Crossfeed(0.34, 0.5, 0.02)
        cf = cf.step(CrossfeedParam.STRENGTH, 1)
        assertEquals(0.35, cf.strength, 0.0)
        repeat(5) { cf = cf.step(CrossfeedParam.SLOPE, -1) }
        assertEquals(DspLimits.SLOPE_MIN, cf.slope, 1e-12)
        repeat(200) { cf = cf.step(CrossfeedParam.RANGE, 1) }
        assertEquals(1.0, cf.range, 0.0)
        var p = b
        repeat(100) { p = p.stepGain(1) }
        assertEquals(DspLimits.GAIN_MAX_DB, p.gainDb, 0.0)
    }

    @Test
    fun `stages move, and moves past the ends do nothing`() {
        val x = DspStage.Crossfeed(0.1)
        val y = DspStage.Crossfeed(0.2)
        val p = b.addStage(x).addStage(y)
        assertEquals(listOf(y, x), p.moveStage(1, -1).stages)
        assertEquals(p, p.moveStage(0, -1))
        assertEquals(listOf(y), p.removeStage(0).stages)
    }

    @Test
    fun `derived crossfeed readouts follow FFmpeg's formulas`() {
        val cf = DspStage.Crossfeed(0.35, 0.8, 0.5)
        assertEquals(420, cf.cutoffHz())
        assertEquals(-10.5, cf.sideCutDb(), 1e-9)
    }

    @Test
    fun `new names take the lowest free number`() {
        val existing = listOf(AudioProfile("1", "Profile 1"), AudioProfile("3", "Profile 3"))
        assertEquals("Profile 2", nextProfileName(existing, "Profile"))
    }

    @Test
    fun `the codec round-trips a state`() {
        val tricky = state.updateProfile("b") { it.copy(name = "Quote \" back\\slash ünïcode\n") }
        assertEquals(tricky, AudioProfileCodec.decode(AudioProfileCodec.encode(tricky)))
    }

    @Test
    fun `the codec reads garbage as empty and drops dangling defaults`() {
        assertEquals(AudioProfilesState(), AudioProfileCodec.decode("{not json"))
        assertEquals(AudioProfilesState(), AudioProfileCodec.decode(""))
        val dangling = """{"version":1,"profiles":[{"id":"x","name":"X","gainDb":0.0,"stages":[{"type":"eq"}]}],"defaults":{"headphones":"gone"}}"""
        val decoded = AudioProfileCodec.decode(dangling)
        assertEquals(listOf(AudioProfile("x", "X")), decoded.profiles)
        assertEquals(emptyMap<AudioOutputKind, String>(), decoded.defaults)
    }

    @Test
    fun `the codec clamps out-of-range values from a hand-edited file`() {
        val text = """{"profiles":[{"id":"x","name":"X","gainDb":99,"stages":[{"type":"crossfeed","strength":3,"range":-1,"slope":0}]}]}"""
        val p = AudioProfileCodec.decode(text).profiles.single()
        assertEquals(DspLimits.GAIN_MAX_DB, p.gainDb, 0.0)
        assertEquals(DspStage.Crossfeed(1.0, 0.0, DspLimits.SLOPE_MIN), p.stages.single())
    }
}
