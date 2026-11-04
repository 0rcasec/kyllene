package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusRequestsTest {

    /** The platform's side: each request made, by its listener, and what it answers. */
    private class Platform {
        val listeners = mutableListOf<(FocusChange) -> Unit>()
        val abandoned = mutableListOf<Int>()
        var grant = FocusGrant.Granted
        val requests = FocusRequests(
            make = { listener -> listeners += listener; listeners.size - 1 },
            ask = { grant },
            abandon = { abandoned += it },
        )
    }

    @Test
    fun aChangeComesToTheReadingThatAsked() {
        val platform = Platform()
        val heard = mutableListOf<FocusChange>()

        assertEquals(FocusGrant.Granted, platform.requests.request { heard += it })
        platform.listeners[0](FocusChange.Duck)
        platform.listeners[0](FocusChange.Gain)

        assertEquals(listOf(FocusChange.Duck, FocusChange.Gain), heard)
    }

    /** Paused (the request let go), then played on: a change queued for the first request reaches nobody. */
    @Test
    fun aChangeForARequestLetGoIsDropped() {
        val platform = Platform()
        val heard = mutableListOf<FocusChange>()

        platform.requests.request { heard += it }
        platform.requests.letGo()
        platform.listeners[0](FocusChange.Gain)
        platform.requests.request { heard += it }
        platform.listeners[0](FocusChange.Loss)

        assertEquals(emptyList(), heard)
        assertEquals(listOf(0), platform.abandoned)
        platform.listeners[1](FocusChange.LossForNow)
        assertEquals(listOf(FocusChange.LossForNow), heard)
    }

    /** A newer reading asks with its own request: the older one's is let go, and its late changes reach nobody. */
    @Test
    fun aNewerReadingsRequestSilencesTheOlderOne() {
        val platform = Platform()
        val first = mutableListOf<FocusChange>()
        val second = mutableListOf<FocusChange>()

        platform.requests.request { first += it }
        platform.requests.request { second += it }
        platform.listeners[0](FocusChange.Loss)

        assertEquals(listOf(0), platform.abandoned)
        assertEquals(emptyList(), first)
        assertEquals(emptyList(), second)
        platform.listeners[1](FocusChange.Duck)
        assertEquals(listOf(FocusChange.Duck), second)
    }

    /** Refused registers nothing: nothing to let go, nothing heard. */
    @Test
    fun aRefusedRequestHoldsNothing() {
        val platform = Platform()
        val heard = mutableListOf<FocusChange>()
        platform.grant = FocusGrant.Refused

        assertEquals(FocusGrant.Refused, platform.requests.request { heard += it })
        platform.listeners[0](FocusChange.Gain)
        platform.requests.letGo()

        assertEquals(emptyList(), heard)
        assertEquals(emptyList(), platform.abandoned)
    }

    /** Delayed is registered: the Gain that comes later is heard, and letting go abandons it once. */
    @Test
    fun aDelayedRequestHearsItsGainAndIsLetGoOnce() {
        val platform = Platform()
        val heard = mutableListOf<FocusChange>()
        platform.grant = FocusGrant.Delayed

        platform.requests.request { heard += it }
        platform.listeners[0](FocusChange.Gain)
        platform.requests.letGo()
        platform.requests.letGo()

        assertEquals(listOf(FocusChange.Gain), heard)
        assertEquals(listOf(0), platform.abandoned)
    }
}
