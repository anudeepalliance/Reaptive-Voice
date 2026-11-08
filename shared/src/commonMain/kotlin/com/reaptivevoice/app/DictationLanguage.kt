package com.reaptivevoice.app

/**
 * A speech language the dictation model knows. [englishName] is the name the model expects in its instruction, for example "American English". An empty name asks the model to detect the language.
 */
data class DictationLanguage(
    val englishName: String,
    val menuLabel: String,
)

/** Languages listed on the LFM3-Audio-2B model card, plus automatic detection. */
val dictationLanguages = listOf(
    DictationLanguage(englishName = "", menuLabel = "Detect the language"),
    DictationLanguage(englishName = "American English", menuLabel = "English, US"),
    DictationLanguage(englishName = "British English", menuLabel = "English, UK"),
    DictationLanguage(englishName = "Arabic", menuLabel = "Arabic"),
    DictationLanguage(englishName = "Chinese", menuLabel = "Chinese"),
    DictationLanguage(englishName = "Danish", menuLabel = "Danish"),
    DictationLanguage(englishName = "Dutch", menuLabel = "Dutch"),
    DictationLanguage(englishName = "Finnish", menuLabel = "Finnish"),
    DictationLanguage(englishName = "French", menuLabel = "French"),
    DictationLanguage(englishName = "German", menuLabel = "German"),
    DictationLanguage(englishName = "Greek", menuLabel = "Greek"),
    DictationLanguage(englishName = "Hebrew", menuLabel = "Hebrew"),
    DictationLanguage(englishName = "Italian", menuLabel = "Italian"),
    DictationLanguage(englishName = "Japanese", menuLabel = "Japanese"),
    DictationLanguage(englishName = "Korean", menuLabel = "Korean"),
    DictationLanguage(englishName = "Polish", menuLabel = "Polish"),
    DictationLanguage(englishName = "Portuguese", menuLabel = "Portuguese"),
    DictationLanguage(englishName = "Spanish", menuLabel = "Spanish"),
    DictationLanguage(englishName = "Swedish", menuLabel = "Swedish"),
    DictationLanguage(englishName = "Turkish", menuLabel = "Turkish"),
)

/**
 * The instruction that puts LFM3-Audio-2B into speech-to-text mode for one language. The person's custom words are listed after it, which in testing made the model spell "Peekopals" right most of the time instead of "PicoPals", without making it put those words where they were not said.
 */
fun buildAsrSystemPrompt(languageEnglishName: String, customWordSpellings: List<String> = emptyList()): String {
    val trimmedLanguageName = languageEnglishName.trim()
    val speechToTextInstruction = if (trimmedLanguageName.isEmpty()) "Perform ASR." else "Perform ASR in $trimmedLanguageName."
    if (customWordSpellings.isEmpty()) {
        return speechToTextInstruction
    }
    return "$speechToTextInstruction Vocabulary: ${customWordSpellings.joinToString(", ")}."
}

/**
 * Removes the speech instruction when the model wrote it into the transcript instead of what was said. It does this on silence or a silent stretch ("Vocabulary: Peekopals, Meera, Ktor."). The custom word list is removed when it appears with its "Vocabulary:" label, or as two or more of the words in the same order with only commas and spaces between them, which nobody says by accident. A single custom word on its own is kept, because the person may really have said it.
 */
fun removeInstructionTheSpeechModelEchoedBack(transcript: String, customWordSpellings: List<String>): String {
    var transcriptWithoutEcho = Regex("Perform ASR(?: in [^.]*)?\\.?", RegexOption.IGNORE_CASE).replace(transcript, " ")
    if (customWordSpellings.isNotEmpty()) {
        val customWordListPattern = customWordSpellings.joinToString("[\\s,]*") { customWordSpelling ->
            Regex.escape(customWordSpelling)
        }
        transcriptWithoutEcho = Regex("Vocabulary\\s*:?\\s*(?:$customWordListPattern)?\\.?", RegexOption.IGNORE_CASE).replace(transcriptWithoutEcho, " ")
        if (customWordSpellings.size >= 2) {
            transcriptWithoutEcho = Regex("(?<![\\p{L}\\p{N}])$customWordListPattern(?![\\p{L}\\p{N}])\\.?", RegexOption.IGNORE_CASE).replace(transcriptWithoutEcho, " ")
        }
    }
    return transcriptWithoutEcho.replace(Regex("\\s+"), " ").trim()
}

/**
 * Removes thinking notes and special tokens so only the spoken words remain.
 */
fun cleanModelTranscript(rawModelText: String): String {
    val withoutThinkingNotes = if (rawModelText.contains("</think>")) {
        rawModelText.substringAfterLast("</think>")
    } else {
        rawModelText
    }
    val withoutSpecialTokens = withoutThinkingNotes.replace(Regex("<\\|[^|>]+\\|>"), "")
    return withoutSpecialTokens.trim().trim('"', '“', '”', '\'')
}
