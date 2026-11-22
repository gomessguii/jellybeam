package tv.jellybeam.player

import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

/** docs/18 §1.2: what one row of the playback error dialog does when chosen. */
sealed interface PlaybackErrorAction {
    /** Retries from the failure point on audio track [trackId] (a [TrackMapping] id). */
    data class SwitchAudio(val trackId: Long) : PlaybackErrorAction

    /** Hands the item to the server's transcode fallback (docs/18 §1). */
    data object Transcode : PlaybackErrorAction

    /** Ends playback, as the fatal path always did. */
    data object Close : PlaybackErrorAction
}

/** One dialog row: [label] plus the track's ` · `-joined [meta], when it is a track. */
data class PlaybackErrorOption(val action: PlaybackErrorAction, val label: String, val meta: String? = null)

/**
 * docs/18 §1.2: the dialog a fatal playback error opens over the paused frame, so the error stays
 * readable and any fallback is the viewer's choice. [failedTrack] names the audio track that
 * failed ("English · FLAC"), null for any other error; [cause] is the exception's own summary.
 */
data class PlaybackErrorState(
    val title: String,
    val message: String,
    val failedTrack: String?,
    val code: String,
    val cause: String?,
    val options: List<PlaybackErrorOption>,
    /** Where playback failed, so a switch resumes there rather than at Media3's idle position. */
    val positionTicks: Long,
)

/** docs/18 §1.2's pure rules for the error dialog; JVM-tested in `PlaybackErrorDialogTest`. */
internal object PlaybackErrorRules {
    private const val CAUSE_MAX_CHARS = 240

    /**
     * Audio tracks worth offering after [failedId] failed: never [failedId] or one that already
     * failed this item ([excludedIds]); the failed track's language first (a viewer rarely wants
     * another language), then the file's default, then file order.
     */
    fun switchCandidates(tracks: List<TrackInfo>, failedId: Long?, excludedIds: Set<Long>): List<TrackInfo> {
        val audio = tracks.filter { it.kind == TrackKindFfi.AUDIO }
        val failedLang = audio.firstOrNull { it.id == failedId }?.lang?.lowercase()?.takeIf { it.isNotBlank() && it != "und" }
        return audio
            .filter { it.id != failedId && it.id !in excludedIds }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<TrackInfo>>(
                    { if (failedLang != null && it.value.lang?.lowercase() == failedLang) 0 else 1 },
                    { if (it.value.isDefault) 0 else 1 },
                    { it.index },
                ),
            )
            .map { it.value }
    }

    /**
     * The failed audio track's id: the one whose Media3 [formatIds] entry matches the renderer's
     * format id, else the selected one; null when neither identifies one.
     */
    fun failedAudioTrackId(tracks: List<TrackInfo>, formatIds: Map<Long, String?>, rendererFormatId: String?): Long? {
        val audio = tracks.filter { it.kind == TrackKindFfi.AUDIO }
        rendererFormatId?.let { wanted -> audio.firstOrNull { formatIds[it.id] == wanted }?.let { return it.id } }
        return audio.firstOrNull { it.isSelected }?.id
    }

    /** The dialog's actions: [switchTo]'s tracks, then Transcode when [transcodeAllowed], then Close, always last. */
    fun actions(switchTo: List<TrackInfo>, transcodeAllowed: Boolean): List<PlaybackErrorAction> =
        switchTo.map { PlaybackErrorAction.SwitchAudio(it.id) } +
            listOfNotNull(PlaybackErrorAction.Transcode.takeIf { transcodeAllowed }) +
            PlaybackErrorAction.Close

    /**
     * The innermost cause's `Type: message`, the line that says what actually broke (Media3 wraps
     * it in renderer and codec exceptions); capped so a format dump can't fill the screen.
     */
    fun causeSummary(error: Throwable): String? {
        var deepest: Throwable? = null
        var next = error.cause
        val seen = HashSet<Throwable>()
        while (next != null && seen.add(next)) {
            deepest = next
            next = next.cause
        }
        val cause = deepest ?: return null
        val message = cause.message?.trim()?.takeIf { it.isNotEmpty() }
        val text = if (message != null) "${cause.javaClass.simpleName}: $message" else cause.javaClass.simpleName
        return if (text.length > CAUSE_MAX_CHARS) text.take(CAUSE_MAX_CHARS - 1) + "…" else text
    }
}
