package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.ReadAloudMedia
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where a reply read aloud is, for the media controls: the pieces played by their real length, the rest estimated
 * at the pace so far, or at [CHARS_PER_SECOND] before any piece has played. Time paused, or waiting for a piece to
 * be voiced, isn't counted. Each change goes to [show].
 */
internal class ReadAloudProgress(
    private val title: String,
    /** The characters of every piece of the reply. */
    private val chars: Int,
    private val time: TimeSource,
    private val show: (ReadAloudMedia) -> Unit,
) {
    private var playedMs = 0L
    private var playedChars = 0
    /** The piece sounding now: what of it played before the last pause, and since when it plays again. */
    private var pieceMs = 0L
    private var since: TimeMark? = null
    private var paused = false
    private var waiting = true

    val positionMs: Long get() = playedMs + pieceMs + (since?.elapsedNow()?.inWholeMilliseconds ?: 0L)

    val durationMs: Long
        get() {
            val msPerChar = if (playedChars > 0 && playedMs > 0) playedMs.toDouble() / playedChars else 1000.0 / CHARS_PER_SECOND
            return maxOf(positionMs, playedMs + ((chars - playedChars).coerceAtLeast(0) * msPerChar).toLong())
        }

    /** A piece starts to play (or waits for the pause to end). */
    fun pieceStarts() {
        pieceMs = 0L
        waiting = false
        since = if (paused) null else time.markNow()
        publish()
    }

    /** The piece that played had [chars] characters: its real length sets the pace for the rest. */
    fun pieceEnds(chars: Int) {
        stopClock()
        playedMs += pieceMs
        playedChars += chars
        pieceMs = 0L
        waiting = true
        publish()
    }

    fun pause(on: Boolean) {
        if (on == paused) return
        if (on) stopClock() else if (!waiting) since = time.markNow()
        paused = on
        publish()
    }

    fun publish() = show(ReadAloudMedia(title, playing = !paused, waiting = waiting, positionMs = positionMs, durationMs = durationMs))

    private fun stopClock() {
        since?.let { pieceMs += it.elapsedNow().inWholeMilliseconds }
        since = null
    }

    companion object {
        /** About how fast a gateway voice reads, until the first piece played gives the real pace. */
        const val CHARS_PER_SECOND = 14.0
        private const val TITLE_CHARS = 60

        /** The start of [text], cut at a word, as the media controls' title. */
        fun title(text: String): String {
            val flat = text.replace(Regex("""\s+"""), " ").trim()
            if (flat.length <= TITLE_CHARS) return flat
            val cut = flat.take(TITLE_CHARS).substringBeforeLast(' ').ifEmpty { flat.take(TITLE_CHARS) }
            return "$cut…"
        }
    }
}
