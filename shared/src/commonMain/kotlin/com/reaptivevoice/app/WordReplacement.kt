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
 * The correct spellings of the person's custom words, such as "Peekopals" and "Meera", each once. Given to the speech model and the cleanup model so they spell those words right in the first place.
 */
fun correctSpellingsOfCustomWords(wordReplacements: List<WordReplacement>): List<String> {
    return wordReplacements
        .map { wordReplacement ->
            wordReplacement.correctWord.trim()
        }
        .filter {
            it.isNotEmpty()
        }
        .distinctBy {
            it.lowercase()
        }
}

/**
 * Swaps every misheard spelling in the transcript for its correct word, then also fixes spellings that look and sound close to a long correct word (see [replaceSpellingsThatSoundLikeALongCorrectWord]), so the person does not have to list every way the model gets it wrong.
 */
fun applyWordReplacements(transcript: String, wordReplacements: List<WordReplacement>): String {
    val transcriptWithListedSpellingsFixed = replaceListedMisheardSpellings(transcript, wordReplacements)
    return replaceSpellingsThatSoundLikeALongCorrectWord(transcriptWithListedSpellingsFixed, wordReplacements)
}

/**
 * Swaps every listed misheard spelling for its correct word. Only whole words are replaced, so "pals" inside "penpals" is left alone. Longer spellings are tried first, so "Peako pals" wins over a shorter "Peako".
 */
private fun replaceListedMisheardSpellings(transcript: String, wordReplacements: List<WordReplacement>): String {
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

/** Correct words shorter than this only replace their listed spellings. Loose matching on short words would hit real words, such as "more" for "Meera" or "keto" for "Ktor". */
private const val shortestCorrectWordForCloseMatching = 6

/** The speech model splits an unknown word into at most this many pieces ("Pico pals", "Pee ko pals"). */
private const val mostWordsAMisheardSpellingSpans = 3

/** A word in the transcript, with any "'s" kept in it, so "Pal's" is one word. */
private val transcriptWordPattern = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")

/**
 * Replaces spellings the person never listed but that clearly mean one of their long correct words, such as "PicoPals", "Pecopals" or "Pico pals" for "Peekopals". A stretch of up to three words joined by spaces or hyphens counts when it sounds the same (see [soundAlikeKey]) and differs by only a few letters. A trailing "'s" is kept ("Pico Pal's" becomes "Peekopals" when the s belongs to the name, "Picopals's" becomes "Peekopals's").
 */
private fun replaceSpellingsThatSoundLikeALongCorrectWord(transcript: String, wordReplacements: List<WordReplacement>): String {
    val longCorrectWords = wordReplacements
        .map { wordReplacement ->
            wordReplacement.correctWord.trim()
        }
        .filter { correctWord ->
            lettersAndDigitsOnly(correctWord).length >= shortestCorrectWordForCloseMatching
        }
        .distinct()
    if (longCorrectWords.isEmpty()) {
        return transcript
    }
    val transcriptWords = transcriptWordPattern.findAll(transcript).toList()
    val correctedTranscript = StringBuilder()
    var copiedUpToIndex = 0
    var wordIndex = 0
    while (wordIndex < transcriptWords.size) {
        val match = findLongestSoundAlikeStretchStartingAt(transcript, transcriptWords, wordIndex, longCorrectWords)
        if (match == null) {
            wordIndex++
            continue
        }
        val firstWord = transcriptWords[wordIndex]
        val lastWord = transcriptWords[wordIndex + match.wordCount - 1]
        correctedTranscript.append(transcript, copiedUpToIndex, firstWord.range.first)
        correctedTranscript.append(match.replacementText)
        copiedUpToIndex = lastWord.range.last + 1
        wordIndex += match.wordCount
    }
    correctedTranscript.append(transcript, copiedUpToIndex, transcript.length)
    return correctedTranscript.toString()
}

/**
 * A stretch of transcript words that should become a correct word.
 *
 * @property wordCount How many transcript words the stretch covers.
 * @property replacementText The correct word, plus "'s" when the stretch ended in a possessive that is not part of the name.
 */
private data class SoundAlikeStretchMatch(
    val wordCount: Int,
    val replacementText: String,
)

/**
 * Tries stretches of one, then two, then three words starting at [firstWordIndex], and returns the first that matches a correct word, or null when none does.
 *
 * Why shortest first: trying the longest first let a correct name swallow the next short word ("Peekopals so much" became "Peekopals much").
 */
private fun findLongestSoundAlikeStretchStartingAt(
    transcript: String,
    transcriptWords: List<MatchResult>,
    firstWordIndex: Int,
    longCorrectWords: List<String>,
): SoundAlikeStretchMatch? {
    val longestStretch = minOf(mostWordsAMisheardSpellingSpans, transcriptWords.size - firstWordIndex)
    for (wordCount in 1..longestStretch) {
        val stretchWords = transcriptWords.subList(firstWordIndex, firstWordIndex + wordCount)
        val areWordsJoinedOnlyBySpacesOrHyphens = stretchWords.zipWithNext().all { (earlierWord, laterWord) ->
            transcript.substring(earlierWord.range.last + 1, laterWord.range.first).all { character ->
                character.isWhitespace() || character == '-'
            }
        }
        if (!areWordsJoinedOnlyBySpacesOrHyphens) {
            continue
        }
        val stretchText = stretchWords.joinToString("") { word ->
            word.value
        }
        val possessiveEnding = Regex("['’]s$", RegexOption.IGNORE_CASE).find(stretchText)?.value
        for (correctWord in longCorrectWords) {
            if (possessiveEnding != null && isCloseSpellingOfCorrectWord(lettersAndDigitsOnly(stretchText.dropLast(possessiveEnding.length)), correctWord)) {
                return SoundAlikeStretchMatch(wordCount, correctWord + possessiveEnding)
            }
            if (isCloseSpellingOfCorrectWord(lettersAndDigitsOnly(stretchText), correctWord)) {
                return SoundAlikeStretchMatch(wordCount, correctWord)
            }
        }
    }
    return null
}

/**
 * True when a heard spelling is very likely the model's attempt at the correct word: it must sound the same and differ by at most a third of the letters. Words of 6 or 7 letters must sound exactly the same; longer words may differ by one sound.
 */
private fun isCloseSpellingOfCorrectWord(heardSpelling: String, correctWord: String): Boolean {
    val heardLetters = heardSpelling.lowercase()
    val correctLetters = lettersAndDigitsOnly(correctWord).lowercase()
    if (heardLetters.isEmpty() || kotlin.math.abs(heardLetters.length - correctLetters.length) > 3) {
        return false
    }
    val heardSoundKey = soundAlikeKey(heardLetters)
    val correctSoundKey = soundAlikeKey(correctLetters)
    if (heardSoundKey.firstOrNull() != correctSoundKey.firstOrNull()) {
        return false
    }
    val allowedSoundDifferences = if (correctLetters.length >= 8) 1 else 0
    return editDistance(heardSoundKey, correctSoundKey) <= allowedSoundDifferences &&
        editDistance(heardLetters, correctLetters) <= correctLetters.length / 3
}

/**
 * A rough spelling of how a word sounds, so different spellings of the same sounds compare equal: "picopals", "peekopals" and "peacopals" all become "pakapals". Every run of vowels becomes "a", hard c and q become k, and doubled letters count once.
 */
private fun soundAlikeKey(lowercaseLetters: String): String {
    val consonantsSpelledAlike = lowercaseLetters
        .replace("ph", "f")
        .replace("ck", "k")
        .replace('c', 'k')
        .replace('q', 'k')
        .replace('z', 's')
        .replace("x", "ks")
    val vowels = setOf('a', 'e', 'i', 'o', 'u', 'y')
    val soundKey = StringBuilder()
    consonantsSpelledAlike.forEach { letter ->
        val sound = if (letter in vowels) 'a' else letter
        if (soundKey.lastOrNull() != sound) {
            soundKey.append(sound)
        }
    }
    return soundKey.toString()
}

/** How many single-letter insertions, deletions or swaps turn one text into the other, such as 2 for "kato" and "ktor". */
fun countLetterChangesBetween(firstText: String, secondText: String): Int {
    return editDistance(firstText, secondText)
}

/** How many single-letter insertions, deletions or swaps turn one text into the other. */
private fun editDistance(firstText: String, secondText: String): Int {
    var previousRow = IntArray(secondText.length + 1) { columnIndex ->
        columnIndex
    }
    for (firstIndex in firstText.indices) {
        val currentRow = IntArray(secondText.length + 1)
        currentRow[0] = firstIndex + 1
        for (secondIndex in secondText.indices) {
            val substitutionCost = if (firstText[firstIndex] == secondText[secondIndex]) 0 else 1
            currentRow[secondIndex + 1] = minOf(
                previousRow[secondIndex + 1] + 1,
                currentRow[secondIndex] + 1,
                previousRow[secondIndex] + substitutionCost,
            )
        }
        previousRow = currentRow
    }
    return previousRow[secondText.length]
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
