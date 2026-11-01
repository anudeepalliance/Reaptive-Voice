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
