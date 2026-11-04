package dev.hermeskotlin.core.voice

/**
 * The audio focus a reading asks for, one request at a time, each with its own listener. The platform queues focus
 * changes, so one can arrive after its request was let go (a Pause, then Play) or after a newer reading asked: each
 * listener speaks only while its request is the one asked now, so a late change reaches nobody. Platform-free so it
 * can be tested; on Android [make] builds the platform's request around the listener, [ask] requests it and
 * [abandon] lets it go.
 */
class FocusRequests<R : Any>(
    private val make: (listener: (FocusChange) -> Unit) -> R,
    private val ask: (R) -> FocusGrant,
    private val abandon: (R) -> Unit,
) {
    /** The request registered with the platform now, given or not yet. */
    private var current: R? = null

    /**
     * Asks anew for [tell]'s reading; a request still registered (another reading's) is let go once the new one is
     * asked, so music beneath stays down across the switch rather than coming up for a moment between the two.
     */
    fun request(tell: (FocusChange) -> Unit): FocusGrant {
        val previous = current
        lateinit var mine: R
        mine = make { change -> if (current === mine) tell(change) }
        current = mine
        val grant = ask(mine)
        // The previous one's listener is already deaf (it isn't current), so what its letting go stirs reaches nobody.
        previous?.let(abandon)
        // Refused registers nothing, so there's nothing to let go or to hear from.
        if (grant == FocusGrant.Refused) current = null
        return grant
    }

    fun letGo() {
        current?.let(abandon)
        current = null
    }
}
