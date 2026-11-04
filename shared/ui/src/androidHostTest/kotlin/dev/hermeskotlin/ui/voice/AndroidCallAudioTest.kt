package dev.hermeskotlin.ui.voice

import android.content.Context
import android.media.AudioManager
import dev.hermeskotlin.core.voice.AndroidCallAudio
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidCallAudioTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** End, then Start at once: the newer chat holds before the older one's wind-down lets go, and keeps its call. */
    @Test fun anOlderChatLettingGoLeavesTheNewerOnesCall() = runBlocking {
        val callAudio = AndroidCallAudio(context)

        assertTrue(callAudio.hold())
        assertTrue(callAudio.hold())
        callAudio.release()

        assertTrue(callAudio.on)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)

        callAudio.release()

        assertFalse(callAudio.on)
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }
}
