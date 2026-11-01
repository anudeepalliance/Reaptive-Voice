package com.reaptivevoice.app

/**
 * One voice Rvoice can read text out loud in. The model is told which accent and gender to use in its prompt ("Perform TTS in British English using a female voice."); there are no separate voice files.
 *
 * @property languageCode The short accent code passed to the model, such as "en-gb".
 * @property promptLanguageName The accent name written into the model's prompt word for word. American and British English are in the model's own list; Indian English is passed through as written, so how strong the accent sounds is up to the model.
 * @property gender "female" or "male".
 */
data class SpeakingVoice(
    val id: String,
    val displayName: String,
    val description: String,
    val languageCode: String,
    val promptLanguageName: String,
    val gender: String,
)

val speakingVoices = listOf(
    SpeakingVoice("american-female", "American female", "Clear US English", "en", "American English", "female"),
    SpeakingVoice("british-female", "British female", "UK English", "en-gb", "British English", "female"),
    SpeakingVoice("indian-female", "Indian female", "Indian English", "en-in", "Indian English", "female"),
)

/** Finds a voice by its saved id; an unknown id falls back to the American female voice. */
fun speakingVoiceForId(voiceId: String): SpeakingVoice {
    return speakingVoices.firstOrNull {
        it.id == voiceId
    } ?: speakingVoices.first()
}
