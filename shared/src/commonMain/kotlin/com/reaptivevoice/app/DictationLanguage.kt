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
 * The instruction that puts LFM3-Audio-2B into speech-to-text mode for one language.
 */
fun buildAsrSystemPrompt(languageEnglishName: String): String {
    val trimmedLanguageName = languageEnglishName.trim()
    if (trimmedLanguageName.isEmpty()) {
        return "Perform ASR."
    }
    return "Perform ASR in $trimmedLanguageName."
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
