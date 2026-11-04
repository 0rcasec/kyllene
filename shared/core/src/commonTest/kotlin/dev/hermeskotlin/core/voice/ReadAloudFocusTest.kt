package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.voice.ReadAloudFocus.Change
import dev.hermeskotlin.core.voice.ReadAloudFocus.Grant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadAloudFocusTest {

    private val grants = ArrayDeque<Grant>()
    private val pauses = mutableListOf<Boolean>()
    private var requests = 0
    private var abandons = 0
    private val focus = ReadAloudFocus(
        request = { requests++; grants.removeFirstOrNull() ?: Grant.Granted },
        abandon = { abandons++ },
        pause = { pauses += it },
    )

    @Test
    fun itPlaysOnceTheFocusIsGiven() {
        focus.wants(true)
        assertEquals(1, requests)
        assertEquals(emptyList(), pauses)
        // Already given: playing on after a pause asks again only because the pause let it go.
        focus.wants(true)
        assertEquals(1, requests)
    }

    /** During a phone call the focus is refused: it doesn't play over the call, and waits for the person. */
    @Test
    fun refusedItStaysPausedUntilThePersonTriesAgain() {
        grants += Grant.Refused
        focus.wants(true)
        assertEquals(listOf(true), pauses)
        focus.changed(Change.Gain)
        assertEquals(listOf(true), pauses)
        focus.wants(true)
        assertEquals(2, requests)
        assertEquals(listOf(true), pauses)
    }

    @Test
    fun delayedItPlaysWhenTheFocusComes() {
        grants += Grant.Delayed
        focus.wants(true)
        assertEquals(listOf(true), pauses)
        focus.changed(Change.Gain)
        assertEquals(listOf(true, false), pauses)
    }

    /** A call comes in, then goes: paused for it, played on after. */
    @Test
    fun aPassingSoundPausesItUntilItEnds() {
        focus.wants(true)
        focus.changed(Change.LossTransient)
        focus.changed(Change.Gain)
        assertEquals(listOf(true, false), pauses)
        assertEquals(0, abandons)
    }

    /**
     * Paused for a call, the person pressed Play (refused, the call goes on) and then Pause: the call ending doesn't
     * play it over their Pause, and their Play asked for the focus again rather than playing over the call.
     */
    @Test
    fun thePersonsLastWordWinsOverTheFocusComingBack() {
        focus.wants(true)
        focus.changed(Change.LossTransient)
        grants += Grant.Refused
        focus.wants(true)
        assertEquals(2, requests)
        focus.wants(false)
        focus.changed(Change.Gain)
        assertEquals(listOf(true, true), pauses)
    }

    /** The person's Pause lets the focus go, so their music comes back; Play asks for it anew. */
    @Test
    fun pausingLetsTheFocusGo() {
        focus.wants(true)
        focus.wants(false)
        assertEquals(1, abandons)
        focus.wants(true)
        assertEquals(2, requests)
        assertEquals(emptyList(), pauses)
    }

    /** Another app plays for good: paused, focus let go, and nothing plays it on by itself. */
    @Test
    fun anotherPlayerForGoodPausesItForThePerson() {
        focus.wants(true)
        focus.changed(Change.Loss)
        focus.changed(Change.Gain)
        assertEquals(listOf(true), pauses)
        assertEquals(1, abandons)
    }

    @Test
    fun aReadingThatEndsLetsTheFocusGo() {
        grants += Grant.Delayed
        focus.wants(true)
        focus.reset()
        focus.changed(Change.Gain)
        assertEquals(listOf(true), pauses)
        assertEquals(1, abandons)
    }
}
