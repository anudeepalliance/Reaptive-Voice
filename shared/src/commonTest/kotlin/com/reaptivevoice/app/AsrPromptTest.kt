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
    fun listsTheCustomWordsAfterTheInstruction() {
        assertEquals(
            "Perform ASR in American English. Vocabulary: Peekopals, Meera, Ktor.",
            buildAsrSystemPrompt("American English", listOf("Peekopals", "Meera", "Ktor")),
        )
    }

    private val customWordSpellings = listOf("Peekopals", "Meera", "Ktor")

    @Test
    fun removesTheWordListTheModelReadsBackOnSilence() {
        assertEquals("", removeInstructionTheSpeechModelEchoedBack("Vocabulary: Peekopals, Meera, Ktor.", customWordSpellings))
        assertEquals("", removeInstructionTheSpeechModelEchoedBack("Peekopals, Meera, Ktor.", customWordSpellings))
        assertEquals("", removeInstructionTheSpeechModelEchoedBack("Perform ASR in American English.", customWordSpellings))
    }

    @Test
    fun keepsRealSpeechAroundAnEchoedWordList() {
        assertEquals(
            "I posted an update. Thanks.",
            removeInstructionTheSpeechModelEchoedBack("I posted an update. Vocabulary: Peekopals, Meera, Ktor. Thanks.", customWordSpellings),
        )
    }

    @Test
    fun keepsCustomWordsThePersonActuallySaid() {
        assertEquals("Peekopals", removeInstructionTheSpeechModelEchoedBack("Peekopals", customWordSpellings))
        assertEquals(
            "Meera and Ktor are both fine, and Peekopals too.",
            removeInstructionTheSpeechModelEchoedBack("Meera and Ktor are both fine, and Peekopals too.", customWordSpellings),
        )
    }

    @Test
    fun dropsThinkingNotesAndSpecialTokens() {
        val cleaned = cleanModelTranscript("<think>checking the audio</think> hello there <|im_end|>")
        assertEquals("hello there", cleaned)
    }
}
