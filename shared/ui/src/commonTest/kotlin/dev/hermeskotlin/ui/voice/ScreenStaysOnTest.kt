package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenStaysOnTest {

    private val listening = VoiceChatState(VoicePhase.Listening)

    @Test
    fun aVoiceChatKeepsTheScreenOnByDefault() {
        assertTrue(screenStaysOn(listening, ReadAloudState(), AppSettings()))
        assertTrue(screenStaysOn(listening.copy(phase = VoicePhase.Thinking), ReadAloudState(), AppSettings()))
    }

    @Test
    fun theSwitchLetsTheScreenSleepThroughAVoiceChat() {
        assertFalse(screenStaysOn(listening, ReadAloudState(), AppSettings(voiceKeepScreenOn = false)))
    }

    @Test
    fun aReplyReadAloudKeepsItOnWhateverTheSwitchSays() {
        assertTrue(screenStaysOn(VoiceChatState(), ReadAloudState(key = "m1"), AppSettings(voiceKeepScreenOn = false)))
    }

    @Test
    fun nothingGoingOnLetsItSleep() {
        assertFalse(screenStaysOn(VoiceChatState(), ReadAloudState(), AppSettings()))
    }
}
