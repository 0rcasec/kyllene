package dev.hermeskotlin.core.voice

/**
 * A voice chat's sound as a phone call has it: played and heard through the car's or the headset's call channel
 * (so their own buttons, mute among them, work on it), else the phone's speaker and microphone, with the phone's
 * call processing. Without it, a voice chat plays like media.
 */
interface CallAudio {
    /** Switches to call audio until [release]; false when it can't. */
    suspend fun hold(): Boolean

    fun release()

    /** No call audio on this platform: a voice chat plays like media. */
    object None : CallAudio {
        override suspend fun hold() = false

        override fun release() = Unit
    }
}
