package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

class PlaybackErrorDialogTest {
    private fun audio(id: Long, lang: String?, default: Boolean = false, selected: Boolean = false) = TrackInfo(
        id = id, kind = TrackKindFfi.AUDIO, title = null, lang = lang, codec = null,
        isDefault = default, isSelected = selected, isForced = false,
    )

    private fun subtitle(id: Long) = TrackInfo(
        id = id, kind = TrackKindFfi.SUBTITLE, title = null, lang = "eng", codec = null,
        isDefault = false, isSelected = false, isForced = false,
    )

    private fun ids(tracks: List<TrackInfo>) = tracks.map { it.id }

    @Test
    fun `the failed track's language comes first, then the default, then file order`() {
        val tracks = listOf(
            audio(1, "eng", default = true, selected = true),
            audio(2, "ita"),
            audio(3, "spa", default = true),
            audio(4, "eng"),
            subtitle(5),
        )
        assertEquals(listOf(4L, 3L, 2L), ids(PlaybackErrorRules.switchCandidates(tracks, failedId = 1, excludedIds = emptySet())))
    }

    @Test
    fun `a track that already failed is never offered again`() {
        val tracks = listOf(audio(1, "eng"), audio(2, "eng"), audio(3, "ita"))
        assertEquals(listOf(3L), ids(PlaybackErrorRules.switchCandidates(tracks, failedId = 2, excludedIds = setOf(1L))))
    }

    @Test
    fun `an unknown or undetermined language ranks no track ahead`() {
        val tracks = listOf(audio(1, "und"), audio(2, "ita"), audio(3, "und"))
        assertEquals(listOf(2L, 3L), ids(PlaybackErrorRules.switchCandidates(tracks, failedId = 1, excludedIds = emptySet())))
        assertEquals(listOf(1L, 2L, 3L), ids(PlaybackErrorRules.switchCandidates(tracks, failedId = null, excludedIds = emptySet())))
    }

    @Test
    fun `a single audio track leaves nothing to switch to`() {
        assertTrue(PlaybackErrorRules.switchCandidates(listOf(audio(1, "eng")), failedId = 1, excludedIds = emptySet()).isEmpty())
    }

    @Test
    fun `the renderer's format id names the failed track, else the selected one`() {
        val tracks = listOf(audio(1, "eng", selected = true), audio(2, "eng"))
        val formatIds = mapOf(1L to "1", 2L to "2")
        assertEquals(2L, PlaybackErrorRules.failedAudioTrackId(tracks, formatIds, rendererFormatId = "2"))
        assertEquals(1L, PlaybackErrorRules.failedAudioTrackId(tracks, formatIds, rendererFormatId = "9"))
        assertEquals(1L, PlaybackErrorRules.failedAudioTrackId(tracks, formatIds, rendererFormatId = null))
        assertNull(PlaybackErrorRules.failedAudioTrackId(listOf(audio(1, "eng")), formatIds, rendererFormatId = null))
    }

    @Test
    fun `rows are the switches, then Transcode only when allowed, then Close`() {
        val switchTo = listOf(audio(4, "eng"), audio(2, "ita"))
        assertEquals(
            listOf(PlaybackErrorAction.SwitchAudio(4), PlaybackErrorAction.SwitchAudio(2), PlaybackErrorAction.Transcode, PlaybackErrorAction.Close),
            PlaybackErrorRules.actions(switchTo, transcodeAllowed = true),
        )
        assertEquals(listOf(PlaybackErrorAction.Close), PlaybackErrorRules.actions(emptyList(), transcodeAllowed = false))
    }

    @Test
    fun `the cause line is the innermost exception, capped`() {
        val inner = IllegalStateException("AudioTrack init failed 0 Config(48000, 6396, 30720)")
        val error = RuntimeException("renderer error", RuntimeException("codec", inner))
        assertEquals("IllegalStateException: AudioTrack init failed 0 Config(48000, 6396, 30720)", PlaybackErrorRules.causeSummary(error))
        assertNull(PlaybackErrorRules.causeSummary(RuntimeException("no cause")))
        assertEquals("IllegalArgumentException", PlaybackErrorRules.causeSummary(RuntimeException("x", IllegalArgumentException())))
        val long = PlaybackErrorRules.causeSummary(RuntimeException("x", IllegalStateException("y".repeat(500))))!!
        assertEquals(240, long.length)
        assertTrue(long.endsWith("…"))
    }
}
