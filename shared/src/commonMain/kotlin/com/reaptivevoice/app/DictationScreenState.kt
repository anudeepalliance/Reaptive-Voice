package com.reaptivevoice.app

/** Where startup of the local speech model currently is. */
enum class DictationEnginePhase {
    Starting,
    Ready,
    MissingModel,
    MissingRuntime,
    Failed,
}

/**
 * One microphone the user can pick. The id is the stable device id from macOS. The name is what the menu shows.
 */
data class MicrophoneChoice(
    val id: String,
    val name: String,
)

/**
 * One finished dictation, newest first in the window. Entries older than seven days are dropped the next time the list is saved.
 *
 * @property spokenAtEpochMilliseconds When the words were dictated, as milliseconds since 1970. Used to decide which entries to delete.
 */
data class DictationTranscriptEntry(
    val id: Long,
    val text: String,
    val spokenAtLabel: String,
    val spokenAtEpochMilliseconds: Long,
    val didInsertIntoFocusedText: Boolean,
)

/**
 * Everything the dictation window draws. The desktop app fills this in as the microphone, the model, and the global shortcut change.
 *
 * @property wordReplacements Custom word fixes applied to every transcript, such as "Peacopals" becoming "Peekopals".
 * @property selectedSpeakingVoiceId The voice used to read text out loud, an id from [speakingVoices].
 * @property isSpeechPaused True while reading out loud is paused.
 * @property speakingSpeed Playback speed for reading out loud, one of [speakingSpeeds].
 * @property savedSpeechAudioFiles MP3s made in the last seven days, newest first.
 * @property speechFileProgress Progress of "Save as MP3" while it runs, otherwise null.
 * @property isSpeakingVoiceReady True once the speaking model has loaded.
 * @property speakingVoiceProblem Why the speaking model could not start, or null when it is fine or still loading.
 * @property savedAudioPlayer What the built-in MP3 player is playing, or null when it is closed.
 */
data class DictationScreenState(
    val enginePhase: DictationEnginePhase,
    val engineDetail: String,
    val modelFootnote: String,
    val isAccessibilityTrusted: Boolean,
    val isGlobalHotkeyRegistered: Boolean,
    val microphoneProblem: String?,
    val microphoneChoices: List<MicrophoneChoice>,
    val selectedMicrophoneId: String,
    val selectedMicrophoneName: String,
    val isListening: Boolean,
    val isTranscribing: Boolean,
    val isSpeaking: Boolean,
    val isSpeakingVoiceReady: Boolean,
    val speakingVoiceProblem: String?,
    val savedAudioPlayer: SavedAudioPlayerState?,
    val selectedSpeakingVoiceId: String,
    val isSpeechPaused: Boolean,
    val speakingSpeed: Float,
    val savedSpeechAudioFiles: List<SavedSpeechAudio>,
    val speechFileProgress: SpeechFileProgress?,
    val listeningElapsedMilliseconds: Long,
    val microphoneLevel: Float,
    val statusLine: String,
    val selectedLanguageEnglishName: String,
    val shortcutKeyCode: Int,
    val shortcutModifiers: Int,
    val shortcutLabel: String,
    val isRecordingShortcut: Boolean,
    val openAtLogin: Boolean,
    val appearanceMode: String,
    val transcriptHistory: List<DictationTranscriptEntry>,
    val wordReplacements: List<WordReplacement>,
    val draftText: String,
    val draftSelectionStart: Int,
    val draftSelectionEnd: Int,
    val draftRevision: Int,
)

fun initialDictationScreenState(): DictationScreenState {
    return DictationScreenState(
        enginePhase = DictationEnginePhase.Starting,
        engineDetail = "Starting LFM3-Audio-2B…",
        modelFootnote = "Checking whether LiquidAI LFM3-Audio-2B is downloaded…",
        isAccessibilityTrusted = false,
        isGlobalHotkeyRegistered = false,
        microphoneProblem = null,
        microphoneChoices = emptyList(),
        selectedMicrophoneId = "",
        selectedMicrophoneName = "System default",
        isListening = false,
        isTranscribing = false,
        isSpeaking = false,
        isSpeakingVoiceReady = false,
        speakingVoiceProblem = null,
        savedAudioPlayer = null,
        selectedSpeakingVoiceId = "american-female",
        isSpeechPaused = false,
        speakingSpeed = 1f,
        savedSpeechAudioFiles = emptyList(),
        speechFileProgress = null,
        listeningElapsedMilliseconds = 0,
        microphoneLevel = 0f,
        statusLine = describeDictationShortcut(defaultDictationShortcut().label),
        selectedLanguageEnglishName = "American English",
        shortcutKeyCode = defaultDictationShortcut().keyCode,
        shortcutModifiers = defaultDictationShortcut().modifiers,
        shortcutLabel = defaultDictationShortcut().label,
        isRecordingShortcut = false,
        openAtLogin = false,
        appearanceMode = "dark",
        transcriptHistory = emptyList(),
        wordReplacements = emptyList(),
        draftText = "",
        draftSelectionStart = 0,
        draftSelectionEnd = 0,
        draftRevision = 0,
    )
}
