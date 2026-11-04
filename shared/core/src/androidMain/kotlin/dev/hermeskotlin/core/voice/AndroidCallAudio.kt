package dev.hermeskotlin.core.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A call's audio routing: communication mode, to the headset (or the car's hands-free) when one is connected,
 * else the speaker, with the phone's call processing. The GPT-Live call and the chained voice chat route the same way.
 */
internal class CallAudioRoute(private val audioManager: AudioManager) {
    private var previousMode = AudioManager.MODE_NORMAL
    private var routed = false

    /** Android wouldn't send the call's sound to the device [route] picked. */
    var refused = false
        private set

    /** Routes the sound as a call; the device it goes to, where the platform says (Android 12 and later). */
    @Suppress("DEPRECATION")
    fun route(): AudioDeviceInfo? {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routed = true
        refused = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSETS }
            return (headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
                ?.also { refused = !audioManager.setCommunicationDevice(it) }
        }
        audioManager.isSpeakerphoneOn = !audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn
        return null
    }

    /** The sound goes to [device] now: Bluetooth takes a moment to open its call channel. */
    fun reached(device: AudioDeviceInfo): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audioManager.communicationDevice?.id == device.id

    /** The microphone that comes with [output] (the headset's, the car's), or null for the phone's own. */
    fun microphoneFor(output: AudioDeviceInfo): AudioDeviceInfo? {
        if (output.type !in HEADSETS) return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == output.type && (output.address.isEmpty() || it.address == output.address) }
    }

    @Suppress("DEPRECATION")
    fun unroute() {
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        else audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousMode
    }

    private companion object {
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}

/**
 * [CallAudio] on Android, for the chained voice chat: routed as a call ([CallAudioRoute]) while it's held. The
 * recorder then listens with the call's microphone ([microphone]) and the player plays as a call ([on]).
 */
class AndroidCallAudio(context: Context) : CallAudio {
    private val route = CallAudioRoute(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    /** Held: the voice plays as a call. */
    @Volatile var on = false
        private set

    /** The call's microphone while held, when it isn't the phone's own (the car's, a headset's). */
    @Volatile var microphone: AudioDeviceInfo? = null
        private set

    /**
     * The chats holding it now. A chat ended and another started at once (End, then Start) can overlap: the newer
     * one holds before the older one's wind-down releases, and that release mustn't unroute the newer one's call.
     */
    private var holders = 0

    /**
     * False, unrouted again, when Android refuses the call's device or it doesn't take the sound in time (a car's
     * call channel that won't open): the voice chat then plays like media, which still reaches it.
     */
    override suspend fun hold(): Boolean {
        if (on) {
            holders++
            return true
        }
        val device = try {
            route.route()
        } catch (_: Exception) {
            route.unroute()
            return false
        }
        on = true
        holders = 1
        val reached = try {
            // What's played or said before the car's call channel opens is lost.
            device == null || !route.refused && withTimeoutOrNull(ROUTE_WAIT_MS) {
                while (!route.reached(device)) delay(ROUTE_POLL_MS)
            } != null
        } catch (e: CancellationException) {
            release()
            throw e
        }
        if (!reached) {
            release()
            return false
        }
        microphone = device?.let(route::microphoneFor)
        return true
    }

    override fun release() {
        if (!on) return
        if (--holders > 0) return
        on = false
        microphone = null
        route.unroute()
    }

    private companion object {
        const val ROUTE_WAIT_MS = 2_000L
        const val ROUTE_POLL_MS = 50L
    }
}
