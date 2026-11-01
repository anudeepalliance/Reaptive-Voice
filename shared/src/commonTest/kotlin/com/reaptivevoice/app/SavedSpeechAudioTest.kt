package com.reaptivevoice.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SavedSpeechAudioTest {
    @Test
    fun labelsSpeedsLikeAPlayer() {
        assertEquals(listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"), speakingSpeeds.map { speakingSpeedLabel(it) })
    }

    @Test
    fun showsLengthsAsMinutesAndSeconds() {
        assertEquals("0:38", formatLengthAsMinutesAndSeconds(38.8))
        assertEquals("2:05", formatLengthAsMinutesAndSeconds(125.0))
    }
}
