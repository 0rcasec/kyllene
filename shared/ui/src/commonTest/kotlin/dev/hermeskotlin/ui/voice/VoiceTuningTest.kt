package dev.hermeskotlin.ui.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceTuningTest {

    @Test
    fun theMeterIsOnASquareRootScale() {
        assertEquals(0.5f, meterPosition(0.25f))
        assertEquals(1f, meterPosition(3f))
        assertEquals(0f, meterPosition(-1f))
    }

    @Test
    fun aDragSetsTheGateToTheLevelUnderIt() {
        for (level in listOf(0.02f, 0.1f, 0.45f, 0.9f)) assertTrue(abs(levelAt(meterPosition(level)) - level) < 1e-5f)
        assertEquals(1f, levelAt(1.5f))
    }

    @Test
    fun theGateIsSetFromTheRoomOnlyWhileItIsTheRoomThatsHeard() {
        val heard = true
        assertTrue(canSetFromNoise(VoiceChatState(VoicePhase.Thinking), heard))
        assertTrue(canSetFromNoise(VoiceChatState(VoicePhase.Listening), heard))
        // Not enough of it yet, someone talking, muted, or the voice playing over it.
        assertFalse(canSetFromNoise(VoiceChatState(VoicePhase.Listening), roomHeard = false))
        assertFalse(canSetFromNoise(VoiceChatState(VoicePhase.Listening, hearing = true), heard))
        assertFalse(canSetFromNoise(VoiceChatState(VoicePhase.Listening, muted = true), heard))
        assertFalse(canSetFromNoise(VoiceChatState(VoicePhase.Speaking), heard))
    }
}
