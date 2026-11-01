package com.reaptivevoice.app

/**
 * One custom word fix: whenever the speech model writes one of the misheard spellings, Rvoice types the correct word instead. Meant for names the model does not know, such as the brand "Peekopals".
 *
 * @property misheardSpellings What the model tends to write, such as "Peacopals" or "Peako pals". Capitals, spaces and hyphens inside a spelling are ignored when matching.
 * @property correctWord Exactly what gets typed, capitals included.
 */
data class WordReplacement(
    val id: Long,
    val misheardSpellings: List<String>,
    val correctWord: String,
)

/**
 * Swaps every misheard spelling in the transcript for its correct word. Only whole words are replaced, so "pals" inside "penpals" is left alone. Longer spellings are tried first, so "Peako pals" wins over a shorter "Peako".
 */
fun applyWordReplacements(transcript: String, wordReplacements: List<WordReplacement>): String {
    val spellingsLongestFirst = wordReplacements
        .flatMap { wordReplacement ->
            wordReplacement.misheardSpellings.map { misheardSpelling ->
                misheardSpelling to wordReplacement.correctWord
            }
        }
        .filter {
            lettersAndDigitsOnly(it.first).isNotEmpty() && it.second.isNotBlank()
        }
        .sortedByDescending {
            lettersAndDigitsOnly(it.first).length
        }
    var correctedTranscript = transcript
    spellingsLongestFirst.forEach { (misheardSpelling, correctWord) ->
        correctedTranscript = buildMisheardSpellingPattern(misheardSpelling).replace(correctedTranscript) {
            correctWord.trim()
        }
    }
    return correctedTranscript
}

/**
 * Turns what the person typed in the "When Rvoice hears" box into separate spellings. Commas separate spellings; blank pieces are dropped.
 */
fun misheardSpellingsFromCommaSeparatedText(commaSeparatedText: String): List<String> {
    return commaSeparatedText
        .split(",")
        .map {
            it.trim()
        }
        .filter {
            lettersAndDigitsOnly(it).isNotEmpty()
        }
}

/**
 * Matches the spelling as a whole word, ignoring capitals, and allows a space or hyphen between any two of its letters, because the model sometimes splits an unknown word ("Peaco pals", "Peaco-pals").
 */
private fun buildMisheardSpellingPattern(misheardSpelling: String): Regex {
    val lettersPattern = lettersAndDigitsOnly(misheardSpelling)
        .map { character ->
            Regex.escape(character.toString())
        }
        .joinToString("[\\s\\-]?")
    return Regex("(?<![\\p{L}\\p{N}])$lettersPattern(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
}

private fun lettersAndDigitsOnly(text: String): String {
    return text.filter { character ->
        character.isLetterOrDigit()
    }
}

/**
 * Adds a word fix. If a fix for the same correct word already exists (ignoring capitals), the new spellings are added to that row instead of making a second row; spellings it already has are not repeated.
 */
fun addOrMergeWordReplacement(
    existingWordReplacements: List<WordReplacement>,
    misheardSpellings: List<String>,
    correctWord: String,
): List<WordReplacement> {
    val trimmedCorrectWord = correctWord.trim()
    if (misheardSpellings.isEmpty() || trimmedCorrectWord.isEmpty()) {
        return existingWordReplacements
    }
    val matchingWordReplacement = existingWordReplacements.firstOrNull {
        it.correctWord.equals(trimmedCorrectWord, ignoreCase = true)
    }
    if (matchingWordReplacement == null) {
        val nextWordReplacementId = (existingWordReplacements.maxOfOrNull { it.id } ?: 0L) + 1
        return existingWordReplacements + WordReplacement(nextWordReplacementId, misheardSpellings, trimmedCorrectWord)
    }
    return existingWordReplacements.map { wordReplacement ->
        if (wordReplacement.id != matchingWordReplacement.id) {
            wordReplacement
        } else {
            wordReplacement.copy(misheardSpellings = withoutRepeatedSpellings(wordReplacement.misheardSpellings + misheardSpellings))
        }
    }
}

/**
 * Replaces one fix's spellings and correct word with edited ones. Leaves the list unchanged when either is left empty.
 */
fun editWordReplacement(
    existingWordReplacements: List<WordReplacement>,
    wordReplacementId: Long,
    misheardSpellings: List<String>,
    correctWord: String,
): List<WordReplacement> {
    val trimmedCorrectWord = correctWord.trim()
    if (misheardSpellings.isEmpty() || trimmedCorrectWord.isEmpty()) {
        return existingWordReplacements
    }
    return existingWordReplacements.map { wordReplacement ->
        if (wordReplacement.id != wordReplacementId) {
            wordReplacement
        } else {
            wordReplacement.copy(misheardSpellings = withoutRepeatedSpellings(misheardSpellings), correctWord = trimmedCorrectWord)
        }
    }
}

/** Keeps the first of any spellings that only differ in capitals or spaces. */
private fun withoutRepeatedSpellings(misheardSpellings: List<String>): List<String> {
    return misheardSpellings.distinctBy {
        lettersAndDigitsOnly(it).lowercase()
    }
}
