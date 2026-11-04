package dev.hermeskotlin.core.voice

/**
 * Keeps a voice chat going while Herald is out of sight (the screen off, another app in front), the way a
 * phone call does. On Android, a microphone foreground service with an ongoing notification.
 */
interface VoiceKeepAlive {
    /**
     * Holds the voice chat up until [release]. [onEnd] runs when the person ends it from outside the app (the
     * notification's End). False when the platform refused at once, and [onLost] runs when it refuses later;
     * either way the chat should end when the app goes away.
     */
    fun hold(onEnd: () -> Unit, onLost: () -> Unit = {}): Boolean

    fun release()
}

/**
 * Keeps a reply being read aloud going while Herald is out of sight, the way music plays on. On Android, a media
 * playback foreground service with an ongoing notification whose Stop ([VoiceKeepAlive.hold]'s onEnd) ends it.
 */
interface ReadAloudKeepAlive : VoiceKeepAlive {
    /**
     * As [hold], with the phone's media controls (the notification, the lock screen, a headset's or a car's buttons):
     * [onPause] true pauses the reading where it is, false plays it on. [onFocus] hears what becomes of the audio
     * focus asked for with [focus], for this reading only: a newer hold, or [release], silences it. Without them, as
     * [hold].
     */
    fun hold(onEnd: () -> Unit, onLost: () -> Unit, onPause: (Boolean) -> Unit, onFocus: (FocusChange) -> Unit): Boolean =
        hold(onEnd, onLost)

    /**
     * Asks for the phone's audio focus for the reading held, answered at once. [FocusGrant.Delayed] (a call, say)
     * comes as [FocusChange.Gain] later. Without a reading held, granted: it plays as it would without focus.
     */
    fun focus(): FocusGrant = FocusGrant.Granted

    /** Lets the focus go, or the request waiting for it, so other apps' sound comes back. */
    fun unfocus() = Unit

    /** Where the reading is, for the media controls' title and progress bar. */
    fun show(media: ReadAloudMedia) = Unit

    /**
     * Ends the reply being read aloud, wherever in the app it was started (the hold's onEnd), as its Stop does:
     * another voice (a call, a voice chat, dictation) is about to take the speaker or the microphone.
     */
    fun end() = Unit
}

/** The answer to [ReadAloudKeepAlive.focus]. */
enum class FocusGrant { Granted, Delayed, Refused }

/** What became of the audio focus a reading asked for. */
enum class FocusChange {
    /** It may play: given at last, or given back. */
    Gain,
    /** Another app plays for good: paused until the person plays it on. */
    Loss,
    /** A call, say: held until the focus comes back. */
    LossForNow,
    /** A passing sound (a notification, a navigation prompt) plays over it: lowered until the focus comes back. */
    Duck,
}

/** A reply read aloud as the media controls show it. */
data class ReadAloudMedia(
    /** The start of the reply. */
    val title: String,
    /** False while the person has it paused. */
    val playing: Boolean,
    /** Waiting for the next piece to be voiced, or for the audio focus: no sound, but it goes on by itself. */
    val waiting: Boolean,
    val positionMs: Long,
    /** An estimate, refined as each piece plays: later pieces don't exist yet. */
    val durationMs: Long,
)
