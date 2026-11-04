package dev.hermeskotlin.android.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.voice.FocusChange
import dev.hermeskotlin.core.voice.FocusGrant
import dev.hermeskotlin.core.voice.FocusRequests
import dev.hermeskotlin.core.voice.ReadAloudKeepAlive
import dev.hermeskotlin.core.voice.ReadAloudMedia
import org.koin.android.ext.android.inject

/**
 * Keeps a reply read aloud playing with the screen off or another app in front, the way music plays on: a media
 * playback foreground service. Read aloud only plays, so it needs no microphone. Its notification's Pause, Play and
 * Stop are the reading's own; the same controls show in Quick Settings and on the lock screen ([ReadAloudKeeper]).
 */
class ReadAloudService : Service() {

    private val keeper: ReadAloudKeeper by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_PAUSE, ACTION_PLAY -> {
                if (intent.action == ACTION_STOP) keeper.end() else keeper.pause(intent.action == ACTION_PAUSE)
                // A late notification button started it afresh, with nothing to read: it doesn't stay behind.
                if (!keeper.inForeground()) stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        // Into the foreground first, always: a service started this way and stopped before it is a crash.
        try {
            ServiceCompat.startForeground(
                this,
                ChatNotifications.READ_ALOUD_ID,
                keeper.notification(),
                // Types are Android 10+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            // Android 12+ refuses a foreground service started from the background.
            Log.w("ReadAloudService", "Couldn't keep reading aloud", e)
            keeper.lost()
            stopSelf()
            return START_NOT_STICKY
        }
        // The reading ended while it was starting: it was left to stop here.
        if (!keeper.started()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keeper.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal companion object {
        const val ACTION_STOP = "dev.hermeskotlin.readaloud.STOP"
        const val ACTION_PAUSE = "dev.hermeskotlin.readaloud.PAUSE"
        const val ACTION_PLAY = "dev.hermeskotlin.readaloud.PLAY"
    }
}

/**
 * [ReadAloudKeepAlive] on Android: holds [ReadAloudService] up while a reply is read aloud, with a media session, so
 * the reading shows in the phone's media controls (Quick Settings, the lock screen) and a headset's or a car's
 * play/pause buttons pause it. No seeking: the pieces ahead aren't voiced yet. It answers the reading's asks for the
 * audio focus at once ([focus]) and tells that reading, and only it, what becomes of the focus; the reading decides
 * (music ducks under it, a call holds it until it ends, a passing sound plays over it lowered). It keeps the CPU
 * awake through the gaps while the next piece is voiced. All on the main thread, as the service's, the session's
 * and the focus callbacks and Read aloud are.
 */
class ReadAloudKeeper(private val context: Context, private val notifications: ChatNotifications) : ReadAloudKeepAlive {

    private var onEnd: (() -> Unit)? = null
    private var onLost: (() -> Unit)? = null
    private var onPause: ((Boolean) -> Unit)? = null
    /** The reading held now, told what becomes of the focus it asked for. */
    private var onFocus: ((FocusChange) -> Unit)? = null
    /** A reply being read holds it. */
    private var wanted = false
    /** The service is in the foreground, so it may be stopped. */
    private var foreground = false
    /** The reading in the phone's media controls, while it's held. */
    private var session: MediaSession? = null
    /** What the media controls show now. */
    private var media: ReadAloudMedia? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** A request of its own for each ask, so a change Android queued for one let go reaches nobody. */
    private val focusRequests = FocusRequests(
        make = { listener ->
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                // During a call the focus can come later; it plays then.
                .setAcceptsDelayedFocusGain(true)
                // On the main thread, as everything else here.
                .setOnAudioFocusChangeListener({ change -> focusChange(change)?.let(listener) }, main)
                .build()
        },
        ask = { request ->
            when (audioManager.requestAudioFocus(request)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusGrant.Granted
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusGrant.Delayed
                else -> FocusGrant.Refused
            }
        },
        abandon = { audioManager.abandonAudioFocusRequest(it) },
    )
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kyllene:ReadAloud")
        .apply { setReferenceCounted(false) }

    override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean = hold(onEnd, onLost, {}, {})

    override fun hold(onEnd: () -> Unit, onLost: () -> Unit, onPause: (Boolean) -> Unit, onFocus: (FocusChange) -> Unit): Boolean = try {
        this.onEnd = onEnd
        this.onLost = onLost
        this.onPause = onPause
        // A newer reading asks for the focus with a request of its own (focus); the one it replaced hears nothing more.
        this.onFocus = onFocus
        wanted = true
        // A newer reply takes over the session the last one had.
        if (session == null) session = openSession()
        ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
        true
    } catch (e: Exception) {
        // Refused, e.g. from the background; the reading then stops when the app goes away.
        Log.w("ReadAloudService", "Couldn't keep reading aloud", e)
        forget()
        false
    }

    override fun show(media: ReadAloudMedia) {
        val was = this.media
        this.media = media
        val session = session ?: return
        if (was?.title != media.title || was.durationMs != media.durationMs) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, media.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Read aloud")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, media.durationMs)
                    .build(),
            )
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                // Android 13+ draws its controls from this state, where only a custom action gives a Stop button.
                .addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Stop", R.drawable.ic_media_stop).build())
                // Waiting for the next piece shows as playing with the bar held still, so Pause still pauses it.
                .setState(
                    if (media.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    media.positionMs,
                    if (media.playing && !media.waiting) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        // Pause turns to Play in the notification (newer Androids draw their controls from the session itself).
        if (foreground && was?.playing != media.playing) notifications.postReadingAloud(notification())
        // Renewed with each piece: a reading that stalls lets the CPU sleep after a while, though it stays held up.
        if (media.playing) wake.acquire(WAKE_MS) else if (wake.isHeld) wake.release()
    }

    override fun release() {
        forget()
        // One still starting stops itself once it's in the foreground (started).
        if (foreground) context.stopService(Intent(context, ReadAloudService::class.java))
    }

    /** The service's notification as the reading stands now. */
    internal fun notification(): Notification {
        val playing = media?.playing ?: true
        return notifications.readingAloud(
            session?.sessionToken,
            media?.title,
            playing,
            playPause = action(if (playing) ReadAloudService.ACTION_PAUSE else ReadAloudService.ACTION_PLAY),
            stop = action(ReadAloudService.ACTION_STOP),
        )
    }

    override fun focus(): FocusGrant {
        // Nothing held (the service was refused): it plays as it would without the focus.
        val tell = onFocus ?: return FocusGrant.Granted
        return focusRequests.request(tell)
    }

    override fun unfocus() = focusRequests.letGo()

    /** The service is in the foreground; false when the reading ended meanwhile, so it should stop. */
    internal fun started(): Boolean {
        foreground = wanted
        return wanted
    }

    internal fun inForeground(): Boolean = foreground

    internal fun stopped() {
        foreground = false
    }

    /** The service couldn't go into the foreground after all. */
    internal fun lost() {
        val lost = onLost
        forget()
        lost?.invoke()
    }

    /** The notification's or the media controls' Stop, or another voice taking the speaker. */
    override fun end() {
        val end = onEnd
        if (end == null) release() else end()
    }

    /** The notification's or the media controls' Pause ([on]) or Play. */
    fun pause(on: Boolean) {
        onPause?.invoke(on)
    }

    private fun openSession() = MediaSession(context, "ReadAloud").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = pause(false)

            override fun onPause() = pause(true)

            override fun onStop() = end()

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == CUSTOM_STOP) end()
            }
        })
        // A tap on the media controls opens the app, as one on the notification does.
        setSessionActivity(notifications.openAppIntent())
        isActive = true
    }

    private fun focusChange(change: Int): FocusChange? = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> FocusChange.Gain
        AudioManager.AUDIOFOCUS_LOSS -> FocusChange.Loss
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LossForNow
        // A notification sound or a navigation prompt: Android lowers music under it itself, but tells speech.
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.Duck
        else -> null
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        context, ACTION_CODES.getValue(name), Intent(context, ReadAloudService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun forget() {
        wanted = false
        onEnd = null
        onLost = null
        onPause = null
        onFocus = null
        media = null
        unfocus()
        if (wake.isHeld) wake.release()
        session?.run {
            isActive = false
            release()
        }
        session = null
    }

    private companion object {
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        const val CUSTOM_STOP = "dev.hermeskotlin.readaloud.STOP"
        /** The longest a reading keeps the CPU awake without another piece starting or ending. */
        const val WAKE_MS = 10 * 60 * 1000L
        /** One request code each, so one action's intent doesn't stand in for another's. */
        val ACTION_CODES = mapOf(ReadAloudService.ACTION_STOP to 0, ReadAloudService.ACTION_PAUSE to 1, ReadAloudService.ACTION_PLAY to 2)
    }
}
