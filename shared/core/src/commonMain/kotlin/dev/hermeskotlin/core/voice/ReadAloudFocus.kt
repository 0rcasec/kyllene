package dev.hermeskotlin.core.voice

/**
 * When a reply read aloud may play, by the phone's audio focus and by what the person last asked for: it plays only
 * once the focus is given, pauses for a sound that takes over (a call, a navigation prompt), plays on once that ends
 * unless the person paused it meanwhile, and lets the focus go when the person pauses, so their music comes back.
 * Platform-free so it can be tested; on Android [ReadAloudKeeper] feeds it and does what it says through [pause].
 */
class ReadAloudFocus(
    /** Asks for the focus; the platform answers at once, or later through [changed] when it's [Grant.Delayed]. */
    private val request: () -> Grant,
    private val abandon: () -> Unit,
    /** Pauses the reading (true) or plays it on: the focus's own doing, not the person's. */
    private val pause: (Boolean) -> Unit,
) {
    enum class Grant { Granted, Delayed, Refused }

    enum class Change { Gain, Loss, LossTransient }

    /** A request is registered with the platform, given or not yet. */
    private var asked = false
    private var granted = false
    /** Paused here for want of the focus: it plays on once the focus comes. */
    private var waiting = false

    /** The person's Play ([playing]) or Pause, from anywhere; a reading starting counts as Play. */
    fun wants(playing: Boolean) {
        waiting = false
        if (!playing) return letGo()
        if (granted) return
        when (request()) {
            Grant.Granted -> {
                asked = true
                granted = true
            }
            Grant.Delayed -> {
                asked = true
                waiting = true
                pause(true)
            }
            // During a phone call, say: it stays paused until the person tries again.
            Grant.Refused -> pause(true)
        }
    }

    fun changed(change: Change) {
        when (change) {
            // Only for a request still registered: one let go hears nothing more.
            Change.Gain -> if (asked) {
                granted = true
                if (waiting) {
                    waiting = false
                    pause(false)
                }
            }
            // A call, or a navigation prompt: speech isn't lowered under another voice, so it pauses until it ends.
            Change.LossTransient -> if (granted) {
                granted = false
                waiting = true
                pause(true)
            }
            // Another app plays now for good: paused until the person plays it on.
            Change.Loss -> {
                val had = granted || waiting
                letGo()
                if (had) pause(true)
            }
        }
    }

    /** The reading is over: the focus goes, and nothing waits for it. */
    fun reset() {
        waiting = false
        letGo()
    }

    private fun letGo() {
        if (asked) abandon()
        asked = false
        granted = false
    }
}
