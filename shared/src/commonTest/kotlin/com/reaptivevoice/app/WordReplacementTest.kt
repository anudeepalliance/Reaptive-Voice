package com.reaptivevoice.app

import kotlin.test.Test
import kotlin.test.assertEquals

class WordReplacementTest {
    private val peekopalsFix = WordReplacement(
        id = 1,
        misheardSpellings = listOf("Peacopals", "Peako pals"),
        correctWord = "Peekopals",
    )

    @Test
    fun replacesTheMisheardBrandName() {
        assertEquals(
            "I love Peekopals so much.",
            applyWordReplacements("I love Peacopals so much.", listOf(peekopalsFix)),
        )
    }

    @Test
    fun ignoresCapitalsSpacesAndHyphensTheModelAdds() {
        assertEquals(
            "Peekopals, Peekopals and Peekopals.",
            applyWordReplacements("peacopals, Peaco pals and PEACO-PALS.", listOf(peekopalsFix)),
        )
    }

    @Test
    fun keepsPunctuationAndPossessivesAroundTheWord() {
        assertEquals(
            "Is Peekopals's app live? (Peekopals)",
            applyWordReplacements("Is Peacopals's app live? (Peacopals)", listOf(peekopalsFix)),
        )
    }

    @Test
    fun leavesWordsThatOnlyContainTheSpellingAlone() {
        val palsFix = WordReplacement(id = 2, misheardSpellings = listOf("pals"), correctWord = "Pals")
        assertEquals("my penpals and Pals", applyWordReplacements("my penpals and pals", listOf(palsFix)))
    }

    @Test
    fun triesTheLongerSpellingFirst() {
        val shortFix = WordReplacement(id = 3, misheardSpellings = listOf("Peako"), correctWord = "Peeko")
        assertEquals(
            "Peekopals and Peeko",
            applyWordReplacements("Peako pals and Peako", listOf(shortFix, peekopalsFix)),
        )
    }

    private val peekopalsFixWithOnlyOneListedSpelling = WordReplacement(id = 10, misheardSpellings = listOf("Peacopals"), correctWord = "Peekopals")

    @Test
    fun fixesUnlistedSpellingsThatSoundLikeALongCorrectWord() {
        assertEquals(
            "Peekopals, Peekopals, Peekopals and Peekopals.",
            applyWordReplacements("PicoPals, Pecopals, Pico pals and pico-pals.", listOf(peekopalsFixWithOnlyOneListedSpelling)),
        )
    }

    @Test
    fun fixesAnUnlistedSpellingThatEndsInApostropheS() {
        assertEquals(
            "Can you check why the Peekopals feed is not loading?",
            applyWordReplacements("Can you check why the Pico Pal's feed is not loading?", listOf(peekopalsFixWithOnlyOneListedSpelling)),
        )
    }

    @Test
    fun keepsThePossessiveOfAnAlreadyCorrectWord() {
        assertEquals(
            "Is Peekopals's app live?",
            applyWordReplacements("Is Peekopals's app live?", listOf(peekopalsFixWithOnlyOneListedSpelling)),
        )
    }

    @Test
    fun leavesOrdinaryWordsNearALongCorrectWordAlone() {
        val sentence = "I picked up my pals, popped a pill and played some pickleball with pickles."
        assertEquals(sentence, applyWordReplacements(sentence, listOf(peekopalsFixWithOnlyOneListedSpelling)))
    }

    @Test
    fun onlyFixesListedSpellingsForShortCorrectWords() {
        val meeraFix = WordReplacement(id = 11, misheardSpellings = listOf("Mira"), correctWord = "Meera")
        val ktorFix = WordReplacement(id = 12, misheardSpellings = listOf("Kator"), correctWord = "Ktor")
        assertEquals(
            "Meera wants more keto food, and the Ktor server is up.",
            applyWordReplacements("Mira wants more keto food, and the Kator server is up.", listOf(meeraFix, ktorFix)),
        )
    }

    @Test
    fun doesNotJoinWordsAcrossPunctuation() {
        assertEquals(
            "I saw Pico. Pals came later.",
            applyWordReplacements("I saw Pico. Pals came later.", listOf(peekopalsFixWithOnlyOneListedSpelling)),
        )
    }

    @Test
    fun splitsTheHeardBoxOnCommas() {
        assertEquals(
            listOf("Peacopals", "Peako pals"),
            misheardSpellingsFromCommaSeparatedText(" Peacopals, Peako pals , ,"),
        )
    }

    @Test
    fun mergesNewSpellingsIntoTheExistingRowForTheSameWord() {
        val merged = addOrMergeWordReplacement(listOf(peekopalsFix), listOf("picopals", "peacopals"), "peekopals")
        assertEquals(1, merged.size)
        assertEquals(listOf("Peacopals", "Peako pals", "picopals"), merged.single().misheardSpellings)
    }

    @Test
    fun addsANewRowForANewWord() {
        val added = addOrMergeWordReplacement(listOf(peekopalsFix), listOf("Reactive voice"), "Rvoice")
        assertEquals(listOf("Peekopals", "Rvoice"), added.map { it.correctWord })
        assertEquals(2L, added.last().id)
    }

    @Test
    fun editsOneRowAndKeepsTheOthers() {
        val otherFix = WordReplacement(id = 7, misheardSpellings = listOf("Reactive voice"), correctWord = "Rvoice")
        val edited = editWordReplacement(listOf(peekopalsFix, otherFix), 1, listOf("Peacopals", "picopals"), "PeekoPals")
        assertEquals(WordReplacement(1, listOf("Peacopals", "picopals"), "PeekoPals"), edited.first())
        assertEquals(otherFix, edited.last())
    }

    @Test
    fun ignoresAnEditThatLeavesABoxEmpty() {
        assertEquals(listOf(peekopalsFix), editWordReplacement(listOf(peekopalsFix), 1, emptyList(), "Peekopals"))
    }
}
