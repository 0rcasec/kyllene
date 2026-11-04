package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.ReadAloudMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ReadAloudProgressTest {

    private val time = TestTimeSource()
    private val shown = mutableListOf<ReadAloudMedia>()

    private fun progress(chars: Int) = ReadAloudProgress("A reply", chars, time) { shown += it }

    /** Before anything played, the length is the text at the usual pace; each piece played sets the real pace. */
    @Test
    fun theLengthIsEstimatedThenTakenFromWhatPlayed() {
        val read = progress(chars = 280)
        read.publish()
        assertEquals(20_000L, shown.last().durationMs)
        assertTrue(shown.last().waiting)

        read.pieceStarts()
        time += 5.seconds
        assertEquals(5_000L, read.positionMs)
        read.pieceEnds(140)

        // 140 characters took 5 s: the other 140 should take 5 s more.
        assertEquals(5_000L, shown.last().positionMs)
        assertEquals(10_000L, shown.last().durationMs)
        assertTrue(shown.last().waiting)
    }

    /** Time paused, and time waiting for the next piece, isn't played time. */
    @Test
    fun pausesAndWaitsDontCount() {
        val read = progress(chars = 140)
        read.pieceStarts()
        time += 2.seconds
        read.hold(true, wanted = false)
        assertFalse(shown.last().playing)
        time += 30.seconds
        assertEquals(2_000L, read.positionMs)
        read.hold(false, wanted = true)
        time += 1.seconds
        read.pieceEnds(70)
        assertEquals(3_000L, read.positionMs)

        time += 4.seconds
        assertEquals(3_000L, read.positionMs)
    }

    /** Paused while waiting for a piece: the piece that comes waits too, and its time starts on play. */
    @Test
    fun aPieceThatStartsPausedStartsCountingOnPlay() {
        val read = progress(chars = 140)
        read.hold(true, wanted = false)
        read.pieceStarts()
        time += 10.seconds
        assertEquals(0L, read.positionMs)
        read.hold(false, wanted = true)
        time += 500.milliseconds
        assertEquals(500L, read.positionMs)
        assertTrue(shown.last().playing)
        assertFalse(shown.last().waiting)
    }

    /**
     * Held for the audio focus (a call) while the person still wants it: the controls show it playing, so Pause is
     * there to press, with the bar still, and the time held isn't played time.
     */
    @Test
    fun heldForTheFocusShowsPlayingWithTheBarStill() {
        val read = progress(chars = 140)
        read.pieceStarts()
        time += 2.seconds
        read.hold(true, wanted = true)
        assertTrue(shown.last().playing)
        assertTrue(shown.last().waiting)
        time += 30.seconds
        assertEquals(2_000L, read.positionMs)
        read.hold(false, wanted = true)
        assertFalse(shown.last().waiting)
        time += 1.seconds
        assertEquals(3_000L, read.positionMs)
    }

    /** A piece that couldn't play: the bar stays where it got to, never back, and the pace isn't taken from it. */
    @Test
    fun aPieceThatFailsLeavesTheBarWhereItWas() {
        val read = progress(chars = 140)
        read.pieceStarts()
        time += 3.seconds
        read.pieceFails(70)
        assertEquals(3_000L, read.positionMs)
        assertTrue(shown.last().waiting)
        time += 5.seconds
        assertEquals(3_000L, read.positionMs)
        // The other 70 characters at the usual pace, after the 3 s the failed piece took.
        assertEquals(8_000L, read.durationMs)
        read.pieceStarts()
        time += 2.seconds
        assertEquals(5_000L, read.positionMs)
    }

    /** The bar never runs past its end, even when a piece plays slower than the estimate. */
    @Test
    fun theLengthIsNeverShorterThanWhatPlayed() {
        val read = progress(chars = 14)
        read.pieceStarts()
        time += 3.seconds
        assertEquals(3_000L, read.durationMs)
    }

    @Test
    fun theTitleIsTheStartOfTheReplyCutAtAWord() {
        assertEquals("Short reply.", ReadAloudProgress.title("  Short\n reply. "))
        val long = "The backup ran at two in the morning and finished in eleven minutes with nothing skipped at all."
        val title = ReadAloudProgress.title(long)
        assertEquals("The backup ran at two in the morning and finished in eleven…", title)
    }
}
