package com.reaptivevoice.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AsrPromptTest {
    @Test
    fun namesTheLanguageTheModelExpects() {
        assertEquals(
            "Perform ASR in American English.",
            buildAsrSystemPrompt("American English"),
        )
    }

    @Test
    fun letsTheModelDetectTheLanguageWhenNoneIsChosen() {
        assertEquals("Perform ASR.", buildAsrSystemPrompt("  "))
    }

    @Test
    fun dropsThinkingNotesAndSpecialTokens() {
        val cleaned = cleanModelTranscript("<think>checking the audio</think> hello there <|im_end|>")
        assertEquals("hello there", cleaned)
    }
}
