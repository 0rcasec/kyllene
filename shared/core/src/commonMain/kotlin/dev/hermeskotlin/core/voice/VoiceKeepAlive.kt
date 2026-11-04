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
     * [onPause] true pauses the reading where it is, false plays it on. Without them, as [hold].
     */
    fun hold(onEnd: () -> Unit, onLost: () -> Unit, onPause: (Boolean) -> Unit): Boolean = hold(onEnd, onLost)

    /** Where the reading is, for the media controls' title and progress bar. */
    fun show(media: ReadAloudMedia) = Unit
}

/** A reply read aloud as the media controls show it. */
data class ReadAloudMedia(
    /** The start of the reply. */
    val title: String,
    /** False while paused. */
    val playing: Boolean,
    /** Waiting for the next piece to be voiced: no sound, but it goes on by itself. */
    val waiting: Boolean,
    val positionMs: Long,
    /** An estimate, refined as each piece plays: later pieces don't exist yet. */
    val durationMs: Long,
)
