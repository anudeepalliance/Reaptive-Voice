package com.reaptivevoice.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

private const val sevenDaysInMilliseconds = 7L * 24 * 60 * 60 * 1000

/** A take stops on its own after 20 minutes, only as a safety net in case the shortcut to stop is never pressed. */
private const val longestTakeInMilliseconds = 20L * 60 * 1000

/** Once the newest, not yet locked-in audio is this long, the older part of it is cut at a pause and locked in. */
private const val unlockedAudioLengthBeforeCuttingInSamples = 25 * speechModelSampleRate

/** The cut is never placed earlier than this into the unlocked audio, so each locked-in part has enough context. */
private const val earliestCutIntoUnlockedAudioInSamples = 15 * speechModelSampleRate

/** Live updates wait until the newest audio is at least this long. */
private const val shortestAudioForLiveUpdateInSamples = speechModelSampleRate * 3 / 2

private enum class DictationSessionPhase {
    Idle,
    Listening,
    Transcribing,
}

/**
 * Runs a dictation take from the global ⌘D shortcut: record, transcribe with LFM3-Audio-2B, then put the words back in the text field that was focused.
 */
class DictationCoordinator {
    var screenState by mutableStateOf(initialDictationScreenState())
        private set

    private val projectRoot = findProjectRoot()
    private val runtimeDirectory = File(System.getProperty("user.home"), ".reaptive-voice")
    private fun modelFootnote(): String {
        if (!modelFile.isFile || !audioProjectorFile.isFile) {
            return "Not downloaded yet. It belongs in ${modelDirectory.path}."
        }
        val totalBytes = modelFile.length() + audioProjectorFile.length()
        return "Downloaded. ${formatByteSize(totalBytes)} total (${formatByteSize(modelFile.length())} weights, ${formatByteSize(audioProjectorFile.length())} audio projector).\n${modelDirectory.path}"
    }

    private fun formatByteSize(byteCount: Long): String {
        val gibibyte = 1024.0 * 1024.0 * 1024.0
        val mebibyte = 1024.0 * 1024.0
        if (byteCount >= gibibyte) {
            return "%.1f GB".format(byteCount / gibibyte)
        }
        return "%.0f MB".format(byteCount / mebibyte)
    }

    private val modelDirectory = File(projectRoot, "models/LFM3-Audio-2B")
    private val modelFile = File(modelDirectory, "LFM3-Audio-2B-Q8_0.gguf")
    private val audioProjectorFile = File(modelDirectory, "mmproj-LFM3-Audio-2B-Q8_0.gguf")
    private val settingsFile = File(runtimeDirectory, "settings.json")
    private val macLoginItem = MacLoginItem(projectRoot)

    private val microphone = MicrophonePcmRecorder(
        launcherBinary = packagedOrProjectFile("ReaptiveVoiceLauncher"),
        recorderBinary = packagedOrProjectFile("ReaptiveVoiceMicrophone"),
        captureFile = File(runtimeDirectory, "captures/latest-dictation.wav"),
        logFile = File(runtimeDirectory, "microphone.log"),
    )
    private val hotkeyBridge = GlobalDictationHotkeyBridge(
        launcherBinary = packagedOrProjectFile("ReaptiveVoiceLauncher"),
        helperExecutable = packagedOrProjectFile("RvoiceHelper.app/Contents/MacOS/ReaptiveVoiceDictationHelper"),
        logFile = File(runtimeDirectory, "dictation-helper.log"),
    )
    private var transcriber: LlamaServerSpeechTranscriber? = null
    private var speechSpeaker: LocalSpeechSpeaker? = null
    private val speechAudioLibrary = SpeechAudioLibrary(File(runtimeDirectory, "speech-audio"))

    /** Turns finished recordings into MP3s one at a time, away from the threads that handle speaking and dictation. */
    private val audioFileThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "speech-audio-files").apply {
            isDaemon = true
        }
    }

    /** Where the person asked each "Save as MP3" file to be saved, keyed by the WAV being made. Files without an entry only go to Saved audio. */
    private val downloadDestinationByRecordingWavPath = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** The text behind each recording still being made, so its MP3 can be titled once the WAV arrives. */
    private val textByRecordingWavPath = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var isSpeakingOutLoud = false

    /** Set the moment Escape is pressed during a take, so no live update or final text gets typed after that. */
    @Volatile
    private var wasTakeCancelled = false

    /** How many times in a row the speaking program failed to start or quit; reset once it is ready. */
    private var speakingVoiceRestartCount = 0
    private val sessionThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictation-session").apply {
            isDaemon = true
        }
    }
    private val clock = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dictation-clock").apply {
            isDaemon = true
        }
    }
    private val nextTranscriptId = AtomicLong(1)
    private val settingsLock = Any()

    @Volatile
    private var sessionPhase = DictationSessionPhase.Idle

    @Volatile
    private var engineIsReady = false

    private var listeningStartedAtMillis = 0L
    private var didScheduleAutoStop = false
    private var clockTask: java.util.concurrent.ScheduledFuture<*>? = null

    fun start() {
        runtimeDirectory.mkdirs()
        loadSettings()
        refreshSavedAudioList()
        updateScreenState { current ->
            current.copy(modelFootnote = modelFootnote())
        }
        ensureHelperBinaryIsBuilt()
        hotkeyBridge.onReady = { accessibilityTrusted, hotkeyRegistered, hotkeyError ->
            File(runtimeDirectory, "dictation-helper.log").appendText(
                "ready accessibility=$accessibilityTrusted hotkey=$hotkeyRegistered\n",
            )
            updateScreenState { current ->
                current.copy(
                    isAccessibilityTrusted = accessibilityTrusted,
                    isGlobalHotkeyRegistered = hotkeyRegistered,
                    statusLine = when {
                        hotkeyRegistered -> describeDictationShortcut(current.shortcutLabel)
                        hotkeyError.isNotBlank() -> hotkeyError
                        else -> current.statusLine
                    },
                )
            }
            hotkeyBridge.setShortcut(screenState.shortcutKeyCode, screenState.shortcutModifiers)
        }
        hotkeyBridge.onShortcutRegistered = { registered, error ->
            updateScreenState { current ->
                current.copy(
                    isGlobalHotkeyRegistered = registered,
                    statusLine = if (registered) {
                        describeDictationShortcut(current.shortcutLabel)
                    } else {
                        error.ifBlank { "That shortcut could not be registered." }
                    },
                )
            }
        }
        hotkeyBridge.onAccessibilityChanged = { trusted ->
            updateScreenState { current ->
                current.copy(isAccessibilityTrusted = trusted)
            }
        }
        hotkeyBridge.onHotkey = { phase, _ ->
            sessionThread.execute {
                if (phase != "ignored" && phase != "cancel" && !acceptHotkeyToggle()) {
                    return@execute
                }
                if (phase == "cancel") {
                    cancelDictationAndKeepWhatIsTyped()
                    return@execute
                }
                if (phase == "start" && isSpeakingOutLoud) {
                    speechSpeaker?.stopSpeaking()
                    hotkeyBridge.setIdle()
                    return@execute
                }
                when (phase) {
                    "start" -> beginListening()
                    "stop" -> finishListening()
                    "ignored" -> showStillWorking()
                }
            }
        }
        hotkeyBridge.onHotkeyCancelRequestedImmediately = {
            wasTakeCancelled = true
        }
        hotkeyBridge.onSavedAudioPlayerState = { playerState ->
            updateScreenState { current ->
                val title = current.savedSpeechAudioFiles.firstOrNull { it.filePath == playerState.filePath }?.title ?: File(playerState.filePath).nameWithoutExtension
                current.copy(savedAudioPlayer = playerState.copy(title = title))
            }
        }
        hotkeyBridge.onSavedAudioPlayerClosed = {
            updateScreenState { current ->
                current.copy(savedAudioPlayer = null)
            }
        }
        hotkeyBridge.onSavedAudioPlayerProblem = { problem ->
            updateScreenState { current ->
                current.copy(savedAudioPlayer = null, statusLine = problem)
            }
        }
        hotkeyBridge.onSpeakRequest = { text ->
            speakTextOutLoud(text)
        }
        hotkeyBridge.onStopSpeakingRequest = {
            stopSpeaking()
        }
        hotkeyBridge.onSaveAudioRequest = { text ->
            saveTextAsMp3(text)
        }
        hotkeyBridge.onSpeechControl = { action ->
            when (action) {
                "next-speed" -> switchToNextSpeakingSpeed()
                "rewind" -> rewindSpeech()
                "toggle-pause" -> toggleSpeechPause()
                "stop" -> stopSpeaking()
            }
        }
        hotkeyBridge.start()
        Thread(
            {
                prepareMicrophone()
            },
            "microphone-permission",
        ).apply {
            isDaemon = true
            start()
        }
        Thread(
            {
                prepareSpeechModel()
            },
            "speech-model",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun selectLanguage(language: DictationLanguage) {
        updateScreenState { current ->
            current.copy(selectedLanguageEnglishName = language.englishName)
        }
        saveSettings()
    }

    fun beginShortcutRecording() {
        updateScreenState { current ->
            current.copy(
                isRecordingShortcut = true,
                statusLine = "Press the new shortcut. Include ⌘, ⌥, ⌃, or ⇧. Escape cancels.",
            )
        }
    }

    fun cancelShortcutRecording() {
        updateScreenState { current ->
            current.copy(
                isRecordingShortcut = false,
                statusLine = describeDictationShortcut(current.shortcutLabel),
            )
        }
    }

    fun applyRecordedShortcut(keyCode: Int, modifiers: Int, label: String) {
        updateScreenState { current ->
            current.copy(
                isRecordingShortcut = false,
                shortcutKeyCode = keyCode,
                shortcutModifiers = modifiers,
                shortcutLabel = label,
                statusLine = describeDictationShortcut(label),
            )
        }
        saveSettings()
        hotkeyBridge.setShortcut(keyCode, modifiers)
    }

    fun setOpenAtLogin(isEnabled: Boolean) {
        val problem = macLoginItem.setEnabled(isEnabled)
        val isActuallyEnabled = macLoginItem.isEnabled()
        updateScreenState { current ->
            current.copy(
                openAtLogin = isActuallyEnabled,
                statusLine = problem ?: if (isActuallyEnabled) {
                    "Rvoice will open when you log in."
                } else {
                    "Rvoice will not open when you log in."
                },
            )
        }
        saveSettings()
    }

    fun refreshMicrophones() {
        val choices = microphone.listInputDevices()
        updateScreenState { current ->
            val selectedStillConnected = current.selectedMicrophoneId.isEmpty() ||
                choices.any { choice ->
                    choice.id == current.selectedMicrophoneId
                }
            current.copy(
                microphoneChoices = choices,
                selectedMicrophoneId = if (selectedStillConnected) {
                    current.selectedMicrophoneId
                } else {
                    ""
                },
                selectedMicrophoneName = if (selectedStillConnected) {
                    current.selectedMicrophoneName
                } else {
                    "System default"
                },
            )
        }
    }

    fun selectMicrophone(microphoneId: String, microphoneName: String) {
        updateScreenState { current ->
            current.copy(
                selectedMicrophoneId = microphoneId,
                selectedMicrophoneName = microphoneName,
                microphoneProblem = null,
            )
        }
        saveSettings()
    }

    fun selectAppearance(appearanceMode: String) {
        updateScreenState { current ->
            current.copy(appearanceMode = appearanceMode)
        }
        saveSettings()
    }

    fun replaceDraft(text: String, selectionStart: Int, selectionEnd: Int) {
        if (isDictationControllingTheDraft || System.currentTimeMillis() < protectDraftUntilMillis) {
            return
        }
        updateScreenState { current ->
            current.copy(
                draftText = text,
                draftSelectionStart = selectionStart,
                draftSelectionEnd = selectionEnd,
            )
        }
    }

    fun promptForAccessibility() {
        hotkeyBridge.promptForAccessibility()
        val helperExecutable = packagedOrProjectFile(
            "RvoiceHelper.app/Contents/MacOS/ReaptiveVoiceDictationHelper",
        )
        val helperApp = helperExecutable.parentFile?.parentFile?.parentFile
        if (helperApp != null && helperApp.isDirectory) {
            ProcessBuilder("open", "-R", helperApp.absolutePath).start()
        }
        ProcessBuilder(
            "open",
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
        ).start()
        updateScreenState { current ->
            current.copy(
                statusLine = "System Settings is open on Accessibility. Turn on Rvoice, then come back.",
            )
        }
    }

    /**
     * Saves a new word fix from the window. Does nothing when either box is empty.
     */
    fun addWordReplacement(misheardSpellingsText: String, correctWord: String) {
        val misheardSpellings = misheardSpellingsFromCommaSeparatedText(misheardSpellingsText)
        val trimmedCorrectWord = correctWord.trim()
        if (misheardSpellings.isEmpty() || trimmedCorrectWord.isEmpty()) {
            return
        }
        updateScreenState { current ->
            current.copy(wordReplacements = addOrMergeWordReplacement(current.wordReplacements, misheardSpellings, trimmedCorrectWord))
        }
        saveSettings()
    }

    /** Saves an edited word fix from the Word replacements tab. Does nothing when either box was left empty. */
    fun saveEditedWordReplacement(wordReplacementId: Long, misheardSpellingsText: String, correctWord: String) {
        updateScreenState { current ->
            current.copy(
                wordReplacements = editWordReplacement(
                    current.wordReplacements,
                    wordReplacementId,
                    misheardSpellingsFromCommaSeparatedText(misheardSpellingsText),
                    correctWord,
                ),
            )
        }
        saveSettings()
    }

    fun removeWordReplacement(wordReplacementId: Long) {
        updateScreenState { current ->
            current.copy(
                wordReplacements = current.wordReplacements.filter {
                    it.id != wordReplacementId
                },
            )
        }
        saveSettings()
    }

    fun copyTranscript(transcript: String) {
        copyTextToClipboard(transcript)
    }

    /**
     * Used only when the global shortcut could not be registered. ⌘D then dictates into this app's own text field.
     */
    /** Escape pressed inside Rvoice's own window during a take: same as Escape anywhere else. */
    fun cancelDictationFromEscapeKey() {
        if (sessionPhase == DictationSessionPhase.Idle) {
            return
        }
        wasTakeCancelled = true
        sessionThread.execute {
            cancelDictationAndKeepWhatIsTyped()
        }
    }

    fun onCommandDInsideThisWindow() {
        sessionThread.execute {
            if (!acceptHotkeyToggle()) {
                return@execute
            }
            when (sessionPhase) {
                DictationSessionPhase.Idle -> beginListening(insertIntoThisApp = true)
                DictationSessionPhase.Listening -> finishListening()
                DictationSessionPhase.Transcribing -> showStillWorking()
            }
        }
    }

    /**
     * Reads the text out loud with the local model. Ignored while a dictation is running, because the microphone would hear the voice and type it.
     */
    fun speakTextOutLoud(text: String) {
        if (text.isBlank()) {
            return
        }
        if (sessionPhase != DictationSessionPhase.Idle) {
            hotkeyBridge.showPill(title = "Finish dictating first", detail = "Rvoice cannot speak while it is listening", tone = "error", microphoneLevel = 0f)
            schedulePillHide()
            return
        }
        val speaker = speechSpeaker
        if (speaker == null || !speaker.isReady) {
            hotkeyBridge.showPill(title = "Voice still loading", detail = "Try again in a few seconds", tone = "working", microphoneLevel = 0f)
            schedulePillHide()
            return
        }
        isSpeakingOutLoud = true
        updateScreenState { current ->
            current.copy(isSpeaking = true)
        }
        updateScreenState { current ->
            current.copy(isSpeechPaused = false)
        }
        if (screenState.speechFileProgress != null) {
            hotkeyBridge.showPill(title = "Making an MP3", detail = "Wait for it to finish or cancel it on the Read aloud page", tone = "working", microphoneLevel = 0f)
            schedulePillHide()
            return
        }
        if (screenState.savedAudioPlayer?.isPlaying == true) {
            hotkeyBridge.toggleSavedAudioPlayback()
        }
        showSpeakingPill(0f)
        val recordingWavFile = speechAudioLibrary.newRecordingWavPath()
        textByRecordingWavPath[recordingWavFile.absolutePath] = text
        speaker.speakTextOutLoud(text, recordingWavFile)
    }

    /**
     * Turns the text into an MP3 without reading it out loud, for long texts to hear later. Progress shows on the Read aloud page; the file appears on the Saved audio page.
     */
    fun suggestedDownloadFileNameForText(text: String): String {
        return speechAudioLibrary.suggestedDownloadFileNameForText(text)
    }

    /**
     * Turns the text into an MP3 without reading it out loud. When `downloadDestination` is given (the place picked in the save window), the finished file is also copied there and shown in Finder; it is kept on the Saved audio tab either way.
     */
    fun saveTextAsMp3(text: String, downloadDestination: File? = null) {
        if (text.isBlank()) {
            return
        }
        val speaker = speechSpeaker
        if (speaker == null || !speaker.isReady) {
            hotkeyBridge.showPill(title = "Voice still loading", detail = "Try again in a few seconds", tone = "working", microphoneLevel = 0f)
            schedulePillHide()
            return
        }
        if (isSpeakingOutLoud) {
            speaker.stopSpeaking()
        }
        val wavFile = speechAudioLibrary.newRecordingWavPath()
        textByRecordingWavPath[wavFile.absolutePath] = text
        if (downloadDestination != null) {
            downloadDestinationByRecordingWavPath[wavFile.absolutePath] = downloadDestination
        }
        updateScreenState { current ->
            current.copy(speechFileProgress = SpeechFileProgress(finishedPartCount = 0, totalPartCount = 0))
        }
        hotkeyBridge.showPill(title = "Making MP3", detail = "", tone = "working", microphoneLevel = 0f)
        schedulePillHide()
        speaker.saveTextToAudioFile(text, wavFile)
    }

    fun cancelSavingMp3() {
        speechSpeaker?.cancelSavingAudioFile()
    }

    fun selectSpeakingSpeed(speed: Float) {
        updateScreenState { current ->
            current.copy(speakingSpeed = speed)
        }
        speechSpeaker?.setPlaybackSpeed(speed)
        if (isSpeakingOutLoud) {
            showSpeakingPill(0f)
        }
        saveSettings()
    }

    /** Moves to the next speed in the list, wrapping from 2x back to 0.5x. Used by the speed button on the pill. */
    fun switchToNextSpeakingSpeed() {
        val currentIndex = speakingSpeeds.indexOf(screenState.speakingSpeed).coerceAtLeast(0)
        selectSpeakingSpeed(speakingSpeeds[(currentIndex + 1) % speakingSpeeds.size])
    }

    /** Re-reads the MP3 folder, deleting files older than seven days, and shows the result on the Saved audio page. */
    fun refreshSavedAudioList() {
        audioFileThread.execute {
            val savedAudioFiles = speechAudioLibrary.listSavedAudioFromDiskAfterDeletingOldOnes()
            updateScreenState { current ->
                current.copy(savedSpeechAudioFiles = savedAudioFiles)
            }
        }
    }

    /** Plays the MP3 in Rvoice's built-in player, stopping anything being read out loud first. */
    fun openSavedAudio(savedAudio: SavedSpeechAudio) {
        if (isSpeakingOutLoud) {
            speechSpeaker?.stopSpeaking()
        }
        hotkeyBridge.openSavedAudioInPlayer(savedAudio.filePath)
    }

    fun toggleSavedAudioPlayback() {
        hotkeyBridge.toggleSavedAudioPlayback()
    }

    /** Jumps back (negative) or forward (positive) in the MP3 being played. */
    fun skipSavedAudioPlayback(seconds: Int) {
        hotkeyBridge.skipSavedAudioPlayback(seconds)
    }

    fun seekSavedAudioPlayback(positionInSeconds: Double) {
        hotkeyBridge.seekSavedAudioPlayback(positionInSeconds)
    }

    fun closeSavedAudioPlayer() {
        hotkeyBridge.closeSavedAudioPlayer()
    }

    /** Starts the speaking program again after it failed, from the Try again button. */
    fun retryStartingSpeakingVoice() {
        speakingVoiceRestartCount = 0
        updateScreenState { current ->
            current.copy(speakingVoiceProblem = null, isSpeakingVoiceReady = false)
        }
        Thread(
            {
                prepareSpeakingVoice()
            },
            "speech-speaker-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Escape during a take: stops the microphone and throws the rest of the recording away. Whatever was already typed stays where it is, and nothing more is typed.
     */
    private fun cancelDictationAndKeepWhatIsTyped() {
        wasTakeCancelled = true
        if (sessionPhase != DictationSessionPhase.Listening) {
            return
        }
        stopClock()
        runCatching {
            microphone.stopAndWriteWav(File(runtimeDirectory, "captures/cancelled.wav"))
        }
        updateScreenState { current ->
            current.copy(isListening = false, isTranscribing = false, microphoneLevel = 0f)
        }
        endWithMessage(title = "Cancelled", detail = "", tone = "error", statusLine = "Dictation cancelled. What was already typed stays.")
    }

    fun deleteSavedAudio(savedAudio: SavedSpeechAudio) {
        audioFileThread.execute {
            speechAudioLibrary.deleteSavedAudioFromDisk(savedAudio)
        }
        refreshSavedAudioList()
    }

    fun suggestedDownloadFileName(savedAudio: SavedSpeechAudio): String {
        return speechAudioLibrary.suggestedDownloadFileName(savedAudio)
    }

    /** Copies the MP3 to the place the person picked in the macOS save window, then shows it in Finder. */
    fun downloadSavedAudio(savedAudio: SavedSpeechAudio, destination: File) {
        audioFileThread.execute {
            val problem = speechAudioLibrary.copySavedAudioToChosenFileOnDisk(savedAudio, destination)
            if (problem == null) {
                ProcessBuilder("open", "-R", destination.absolutePath).start()
            }
            updateScreenState { current ->
                current.copy(statusLine = problem ?: "Saved ${destination.name} to ${destination.parentFile?.name ?: "the folder you chose"}.")
            }
        }
    }

    /** Turns a finished WAV into an MP3 in the background, then refreshes the Saved audio page. */
    private fun convertRecordingToMp3(wavFile: File, lengthInSeconds: Double, wasSpokenOutLoud: Boolean) {
        val spokenText = textByRecordingWavPath.remove(wavFile.absolutePath).orEmpty()
        val downloadDestination = downloadDestinationByRecordingWavPath.remove(wavFile.absolutePath)
        audioFileThread.execute {
            val mp3File = speechAudioLibrary.convertWavRecordingToMp3OnDisk(wavFile, spokenText, lengthInSeconds, wasSpokenOutLoud)
            if (downloadDestination != null) {
                val problem = if (mp3File == null) {
                    "The MP3 could not be made. Is lame installed? (brew install lame)"
                } else {
                    runCatching {
                        mp3File.copyTo(downloadDestination, overwrite = true)
                        ProcessBuilder("open", "-R", downloadDestination.absolutePath).start()
                    }.exceptionOrNull()?.message
                }
                updateScreenState { current ->
                    current.copy(statusLine = problem ?: "Saved ${downloadDestination.name}.")
                }
                if (problem != null) {
                    hotkeyBridge.showPill(title = "Could not save the MP3", detail = problem, tone = "error", microphoneLevel = 0f)
                    schedulePillHide()
                }
            }
            updateScreenState { current ->
                current.copy(savedSpeechAudioFiles = speechAudioLibrary.listSavedAudioFromDiskAfterDeletingOldOnes())
            }
        }
    }

    /**
     * Reads whatever text is on the clipboard out loud. Does nothing when the clipboard holds no text.
     */
    fun speakClipboardOutLoud() {
        val clipboardText = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull().orEmpty()
        if (clipboardText.isBlank()) {
            hotkeyBridge.showPill(title = "Nothing to read", detail = "Copy some text first", tone = "error", microphoneLevel = 0f)
            schedulePillHide()
            return
        }
        speakTextOutLoud(clipboardText)
    }

    fun stopSpeaking() {
        speechSpeaker?.stopSpeaking()
    }

    fun selectSpeakingVoice(voice: SpeakingVoice) {
        updateScreenState { current ->
            current.copy(selectedSpeakingVoiceId = voice.id)
        }
        sendChosenVoiceToSpeaker()
        saveSettings()
    }

    /**
     * Picks the voice and reads a short sample in it, so the person can hear it before using it.
     */
    fun previewSpeakingVoice(voice: SpeakingVoice) {
        selectSpeakingVoice(voice)
        speakTextOutLoud("Hi! This is the ${voice.displayName} voice. This is how Rvoice will read your text out loud.")
    }

    /** Pauses reading out loud, or continues if it is paused. Does nothing when nothing is being read. */
    fun toggleSpeechPause() {
        val speaker = speechSpeaker ?: return
        if (!isSpeakingOutLoud) {
            return
        }
        if (screenState.isSpeechPaused) {
            speaker.resumeSpeaking()
        } else {
            speaker.pauseSpeaking()
        }
    }

    /** Jumps back five seconds in what is being read out loud. */
    fun rewindSpeech() {
        if (isSpeakingOutLoud) {
            speechSpeaker?.jumpBackWhileSpeaking(5)
        }
    }

    private fun sendChosenVoiceToSpeaker() {
        speechSpeaker?.chooseVoice(speakingVoiceForId(screenState.selectedSpeakingVoiceId))
    }

    private fun showSpeakingPill(loudness: Float) {
        hotkeyBridge.showPill(
            title = "Speaking",
            detail = (if (screenState.isSpeechPaused) "paused" else "playing") + "|" + speakingSpeedLabel(screenState.speakingSpeed),
            tone = "speaking",
            microphoneLevel = loudness,
        )
    }

    /**
     * Starts the speaking program once the listening model is up, so the two do not load at the same moment. The pill follows the voice's loudness while it speaks.
     */
    private fun prepareSpeakingVoice() {
        val speaker = LocalSpeechSpeaker(
            speakerBinary = packagedOrProjectFile("RvoiceSpeaker"),
            modelFile = modelFile,
            audioProjectorFile = audioProjectorFile,
            logFile = File(runtimeDirectory, "speaker.log"),
        )
        speaker.onLoudness = { loudness ->
            if (isSpeakingOutLoud) {
                showSpeakingPill(loudness)
            }
        }
        speaker.onPausedOrResumed = { isPaused ->
            updateScreenState { current ->
                current.copy(isSpeechPaused = isPaused)
            }
            if (isSpeakingOutLoud) {
                showSpeakingPill(0f)
            }
        }
        speaker.onFinishedSpeaking = { _ ->
            isSpeakingOutLoud = false
            updateScreenState { current ->
                current.copy(isSpeaking = false, isSpeechPaused = false)
            }
            if (sessionPhase == DictationSessionPhase.Idle) {
                hotkeyBridge.hidePill()
            }
        }
        speaker.onProblem = { problem ->
            hotkeyBridge.showPill(title = "Could not speak", detail = problem, tone = "error", microphoneLevel = 0f)
            schedulePillHide()
        }
        speaker.onReady = {
            speakingVoiceRestartCount = 0
            updateScreenState { current ->
                current.copy(isSpeakingVoiceReady = true, speakingVoiceProblem = null)
            }
        }
        speaker.onSpeakerProgramQuit = {
            isSpeakingOutLoud = false
            updateScreenState { current ->
                current.copy(isSpeakingVoiceReady = false, isSpeaking = false, speechFileProgress = null)
            }
            restartSpeakingVoiceOrReportProblem("The speaking program stopped. Details are in ~/.reaptive-voice/speaker.log.")
        }
        speaker.onRecordedWhatWasSpoken = { wavFile ->
            convertRecordingToMp3(wavFile, lengthInSeconds = 0.0, wasSpokenOutLoud = true)
        }
        speaker.onSaveProgress = { finishedPartCount, totalPartCount ->
            updateScreenState { current ->
                current.copy(speechFileProgress = SpeechFileProgress(finishedPartCount, totalPartCount))
            }
        }
        speaker.onSaved = { wavFile, lengthInSeconds ->
            updateScreenState { current ->
                current.copy(speechFileProgress = null)
            }
            hotkeyBridge.showPill(title = "MP3 ready", detail = "", tone = "done", microphoneLevel = 0f)
            schedulePillHide()
            convertRecordingToMp3(wavFile, lengthInSeconds, wasSpokenOutLoud = false)
        }
        speaker.onSaveFailed = { problem ->
            updateScreenState { current ->
                current.copy(speechFileProgress = null, statusLine = problem)
            }
            hotkeyBridge.showPill(title = "Could not make the MP3", detail = problem, tone = "error", microphoneLevel = 0f)
            schedulePillHide()
        }
        speaker.onSaveCancelled = {
            updateScreenState { current ->
                current.copy(speechFileProgress = null)
            }
        }
        speaker.onBusy = { problem ->
            isSpeakingOutLoud = false
            updateScreenState { current ->
                current.copy(isSpeaking = false)
            }
            hotkeyBridge.showPill(title = "Making an MP3", detail = problem, tone = "error", microphoneLevel = 0f)
            schedulePillHide()
        }
        speechSpeaker = speaker
        val problem = try {
            speaker.startLoadingSpeechModel()
        } catch (exception: Exception) {
            exception.message ?: "The speaking program could not be started."
        }
        if (problem != null) {
            speechSpeaker = null
            restartSpeakingVoiceOrReportProblem(problem)
            return
        }
        sendChosenVoiceToSpeaker()
        speaker.setPlaybackSpeed(screenState.speakingSpeed)
    }

    /**
     * Tries starting the speaking program again after two seconds, up to three times in a row. After that the Speech page shows the reason and a Try again button.
     */
    private fun restartSpeakingVoiceOrReportProblem(problem: String) {
        speakingVoiceRestartCount++
        if (speakingVoiceRestartCount > 3) {
            updateScreenState { current ->
                current.copy(speakingVoiceProblem = problem, isSpeakingVoiceReady = false)
            }
            return
        }
        clock.schedule(
            {
                prepareSpeakingVoice()
            },
            2,
            java.util.concurrent.TimeUnit.SECONDS,
        )
    }

    fun shutdown() {
        stopClock()
        if (sessionPhase == DictationSessionPhase.Listening) {
            runCatching {
                microphone.stopAndWriteWav(File(runtimeDirectory, "captures/discarded.wav"))
            }
        }
        hotkeyBridge.shutdown()
        speechSpeaker?.shutdown()
        audioFileThread.shutdown()
        transcriber?.shutdown()
        sessionThread.shutdownNow()
        clock.shutdownNow()
    }

    private fun beginListening(insertIntoThisApp: Boolean = false) {
        if (sessionPhase != DictationSessionPhase.Idle) {
            return
        }
        if (!engineIsReady) {
            hotkeyBridge.setIdle()
            hotkeyBridge.showPill(
                title = "Still loading",
                detail = "The speech model is not ready yet",
                tone = "working",
                microphoneLevel = 0f,
            )
            schedulePillHide()
            updateScreenState { current ->
                current.copy(statusLine = "The speech model is still loading.")
            }
            return
        }
        val microphoneProblem = microphone.start(screenState.selectedMicrophoneId)
        if (microphoneProblem != null) {
            hotkeyBridge.setIdle()
            hotkeyBridge.showPill(
                title = "No microphone",
                detail = "Check System Settings",
                tone = "error",
                microphoneLevel = 0f,
            )
            schedulePillHide()
            updateScreenState { current ->
                current.copy(
                    microphoneProblem = microphoneProblem,
                    statusLine = microphoneProblem,
                )
            }
            return
        }
        sessionPhase = DictationSessionPhase.Listening
        listeningStartedAtMillis = System.currentTimeMillis()
        didScheduleAutoStop = false
        forcedInsertIntoThisApp = insertIntoThisApp
        alreadyTypedTranscript = ""
        alreadyTypedIntoAnotherApp = ""
        didInsertExternalTextThisTake = false
        lastInsertFailureMessage = ""
        lastLiveTranscriptionAtMillis = 0L
        wasTakeCancelled = false
        lockedInAudioSampleCount = 0
        audioSampleCountAtLastLiveUpdate = 0
        lockedInTranscript = ""
        didTypingStopBecauseFocusMoved = false
        isDictationControllingTheDraft = true
        startClock()
        updateScreenState { current ->
            draftTextAtTakeStart = current.draftText
            selectionStartAtTakeStart = current.draftSelectionStart.coerceIn(0, current.draftText.length)
            selectionEndAtTakeStart = current.draftSelectionEnd.coerceIn(
                selectionStartAtTakeStart,
                current.draftText.length,
            )
            current.copy(
                isListening = true,
                isTranscribing = false,
                listeningElapsedMilliseconds = 0,
                microphoneProblem = null,
                statusLine = "Listening. Words appear as you speak. Press ${screenState.shortcutLabel} when you are done.",
            )
        }
        hotkeyBridge.showPill(
            title = "Listening",
            detail = "0:00",
            tone = "listening",
            microphoneLevel = 0f,
        )
    }

    @Volatile
    private var forcedInsertIntoThisApp = false

    @Volatile
    private var isDictationControllingTheDraft = false

    @Volatile
    private var protectDraftUntilMillis = 0L

    private var draftTextAtTakeStart = ""
    private var selectionStartAtTakeStart = 0
    private var selectionEndAtTakeStart = 0
    private var alreadyTypedTranscript = ""
    private var alreadyTypedIntoAnotherApp = ""
    private var didInsertExternalTextThisTake = false
    private var lastInsertFailureMessage = ""
    private var lastLiveTranscriptionAtMillis = 0L
    private var lockedInAudioSampleCount = 0
    private var audioSampleCountAtLastLiveUpdate = 0
    private var lockedInTranscript = ""
    private var didTypingStopBecauseFocusMoved = false

    @Volatile
    private var isLiveTranscriptionQueued = false

    private var lastHotkeyAtMillis = 0L

    /**
     * Drops a second ⌘D that arrives immediately after the first. That happens when the system shortcut and the window both see the same key.
     */
    private fun acceptHotkeyToggle(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHotkeyAtMillis < 120) {
            return false
        }
        lastHotkeyAtMillis = now
        return true
    }

    private fun finishListening() {
        if (sessionPhase != DictationSessionPhase.Listening) {
            return
        }
        sessionPhase = DictationSessionPhase.Transcribing
        stopClock()
        val insertIntoThisApp = forcedInsertIntoThisApp
        forcedInsertIntoThisApp = false
        val captureFile = File(runtimeDirectory, "captures/latest-dictation.wav")
        val recordingProblem = microphone.stopAndWriteWav(captureFile)
        updateScreenState { current ->
            current.copy(
                isListening = false,
                isTranscribing = true,
                microphoneLevel = 0f,
                statusLine = "Transcribing with LFM3-Audio-2B…",
            )
        }
        hotkeyBridge.showPill(
            title = "Transcribing",
            detail = "LFM3-Audio-2B",
            tone = "working",
            microphoneLevel = 0f,
        )
        if (recordingProblem != null) {
            endWithMessage(title = "Try again", detail = recordingProblem, tone = "error", statusLine = recordingProblem)
            return
        }
        val transcript = try {
            transcribeTakeUsingLockedInParts(readPcm16MonoWavSamplesFromDisk(captureFile), isFinalPass = true)
        } catch (exception: Exception) {
            endWithMessage(
                title = "Transcription failed",
                detail = exception.message ?: "The speech model returned an error.",
                tone = "error",
                statusLine = exception.message ?: "The speech model returned an error.",
            )
            return
        }
        val words = transcript.trim().ifEmpty { alreadyTypedTranscript }
        if (words.isEmpty()) {
            endWithMessage(
                title = "Heard nothing",
                detail = "Try speaking a little louder",
                tone = "error",
                statusLine = "The model did not return any words.",
            )
            return
        }
        if (wasTakeCancelled) {
            endWithMessage(title = "Cancelled", detail = "", tone = "error", statusLine = "Dictation cancelled. What was already typed stays.")
            return
        }
        val didTypeIntoAField = typeNewlySpokenWords(words, insertIntoThisApp, isFinalText = true)
        val didFinishTypingInTheSameField = didTypeIntoAField && !didTypingStopBecauseFocusMoved
        if (!didFinishTypingInTheSameField) {
            copyTextToClipboard(words)
        }
        rememberTranscript(words, didFinishTypingInTheSameField)
        val statusLine = when {
            didFinishTypingInTheSameField -> "Typed into the focused field."
            lastInsertFailureMessage.isNotBlank() -> lastInsertFailureMessage
            else -> "Copied to the clipboard and saved in Recent."
        }
        val pillTitle = if (didFinishTypingInTheSameField) "Inserted" else "Copied"
        val pillDetail = if (didFinishTypingInTheSameField) words.take(48) else statusLine.take(80)
        endWithMessage(
            title = pillTitle,
            detail = pillDetail,
            tone = if (didFinishTypingInTheSameField) "done" else "error",
            statusLine = statusLine,
            keepTranscriptState = true,
        )
    }

    private fun showStillWorking() {
        hotkeyBridge.showPill(
            title = "Still writing",
            detail = "The last phrase is not done yet",
            tone = "working",
            microphoneLevel = 0f,
        )
        schedulePillHide()
    }

    private fun endWithMessage(
        title: String,
        detail: String,
        tone: String,
        statusLine: String,
        keepTranscriptState: Boolean = false,
    ) {
        sessionPhase = DictationSessionPhase.Idle
        hotkeyBridge.setIdle()
        hotkeyBridge.showPill(title = title, detail = detail, tone = tone, microphoneLevel = 0f)
        schedulePillHide()
        updateScreenState { current ->
            current.copy(
                isListening = false,
                isTranscribing = false,
                microphoneLevel = 0f,
                statusLine = statusLine,
                transcriptHistory = if (keepTranscriptState) current.transcriptHistory else current.transcriptHistory,
            )
        }
        protectDraftUntilMillis = System.currentTimeMillis() + 1_500
        Thread(
            {
                Thread.sleep(1_500)
                isDictationControllingTheDraft = false
            },
            "release-draft",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Sends the audio recorded so far to the model and puts the whole text heard so far into the focused field. Skipped while the person is silent: re-sending the same words with more silence after them makes the model reword slightly, which would erase and retype the text over and over.
     */
    private fun transcribeWhatHasBeenSaidSoFar() {
        if (sessionPhase != DictationSessionPhase.Listening || didTypingStopBecauseFocusMoved) {
            return
        }
        val partialRecording = microphone.snapshotForLiveTranscription() ?: return
        if (sessionPhase != DictationSessionPhase.Listening) {
            return
        }
        val allSamples = readPcm16MonoWavSamplesFromDisk(partialRecording)
        val audioSinceLastLiveUpdate = allSamples.copyOfRange(audioSampleCountAtLastLiveUpdate.coerceAtMost(allSamples.size), allSamples.size)
        if (alreadyTypedTranscript.isNotEmpty() && !containsSpeech(audioSinceLastLiveUpdate, minimumSpeechMilliseconds = 200)) {
            return
        }
        audioSampleCountAtLastLiveUpdate = allSamples.size
        val transcript = try {
            transcribeTakeUsingLockedInParts(allSamples, isFinalPass = false)
        } catch (_: Exception) {
            return
        }
        if (sessionPhase != DictationSessionPhase.Listening) {
            return
        }
        typeNewlySpokenWords(transcript, forcedInsertIntoThisApp, isFinalText = false)
    }

    /**
     * Puts the whole sentence heard so far into the focused field, replacing the earlier fragment from this take.
     */
    private fun typeNewlySpokenWords(latestTranscript: String, insertIntoThisApp: Boolean, isFinalText: Boolean): Boolean {
        val latestWords = latestTranscript.trim()
        if (latestWords.isEmpty()) {
            return didInsertExternalTextThisTake
        }
        writeFullTranscriptIntoDraft(latestWords)
        if (insertIntoThisApp) {
            didInsertExternalTextThisTake = true
            return true
        }
        if (didTypingStopBecauseFocusMoved || wasTakeCancelled) {
            return didInsertExternalTextThisTake
        }
        val insertOutcome = hotkeyBridge.insertTranscript("$latestWords ", isFinalText)
        if (insertOutcome.method == "caret-moved") {
            didTypingStopBecauseFocusMoved = true
        }
        if (insertOutcome.method == "compose-window" || insertOutcome.didInsert) {
            didInsertExternalTextThisTake = true
            alreadyTypedIntoAnotherApp = latestWords
            lastInsertFailureMessage = ""
            return true
        }
        if (insertOutcome.method == "waiting-for-final") {
            return didInsertExternalTextThisTake
        }
        lastInsertFailureMessage = insertOutcome.message
        /* For the final text only its own result counts; an earlier live update having landed does not mean the finished sentence did. */
        return if (isFinalText) false else didInsertExternalTextThisTake
    }

    /**
     * Turns the take into text without sending older audio to the model again. Once the newest audio passes 25 seconds, it is cut at a pause and the part before the cut is transcribed once and locked in. Only the audio after the last cut is sent on each live update, so updates stay quick and only the last sentence or so can still change.
     *
     * Live updates skip the newest audio until it holds real speech, because on silence the model tends to guess "Okay.". The final pass of a short take always sends the whole recording, as before.
     */
    private fun transcribeTakeUsingLockedInParts(allSamples: ShortArray, isFinalPass: Boolean): String {
        val languageEnglishName = screenState.selectedLanguageEnglishName
        while (allSamples.size - lockedInAudioSampleCount >= unlockedAudioLengthBeforeCuttingInSamples) {
            val cutPoint = findQuietestCutPoint(
                allSamples,
                lockedInAudioSampleCount + earliestCutIntoUnlockedAudioInSamples,
                allSamples.size - speechModelSampleRate,
            )
            val olderAudio = allSamples.copyOfRange(lockedInAudioSampleCount, cutPoint)
            val olderText = if (containsSpeech(olderAudio)) {
                transcribeSamplesWithSpeechModel(olderAudio, languageEnglishName, "locked-in-part.wav")
            } else {
                ""
            }
            lockedInTranscript = joinTranscriptParts(lockedInTranscript, olderText)
            lockedInAudioSampleCount = cutPoint
        }
        val newestAudio = allSamples.copyOfRange(lockedInAudioSampleCount.coerceAtMost(allSamples.size), allSamples.size)
        val shouldSendNewestAudio = when {
            isFinalPass && lockedInTranscript.isEmpty() -> newestAudio.isNotEmpty()
            isFinalPass -> containsSpeech(newestAudio)
            else -> newestAudio.size >= shortestAudioForLiveUpdateInSamples && containsSpeech(newestAudio)
        }
        val newestText = if (shouldSendNewestAudio) {
            transcribeSamplesWithSpeechModel(newestAudio, languageEnglishName, "newest-part.wav")
        } else {
            ""
        }
        return applyWordReplacements(joinTranscriptParts(lockedInTranscript, newestText), screenState.wordReplacements)
    }

    private fun transcribeSamplesWithSpeechModel(samples: ShortArray, languageEnglishName: String, wavFileName: String): String {
        val speechTranscriber = transcriber ?: return ""
        val wavFile = File(runtimeDirectory, "captures/$wavFileName")
        writePcm16MonoWav(wavFile, samples, speechModelSampleRate)
        return speechTranscriber.transcribeWavFile(wavFile, languageEnglishName).trim()
    }

    private fun joinTranscriptParts(earlierText: String, laterText: String): String {
        return listOf(earlierText.trim(), laterText.trim())
            .filter {
                it.isNotEmpty()
            }
            .joinToString(" ")
    }

    /**
     * Puts the whole transcript so far into the Rvoice box, starting from the text that was there when this take began. Each pass replaces the previous guess, so "Hello there." becomes the finished sentence.
     */
    private fun writeFullTranscriptIntoDraft(latestWords: String) {
        val textToType = "$latestWords "
        updateScreenState { current ->
            val start = selectionStartAtTakeStart.coerceIn(0, draftTextAtTakeStart.length)
            val end = selectionEndAtTakeStart.coerceIn(start, draftTextAtTakeStart.length)
            val updatedText = draftTextAtTakeStart.replaceRange(start, end, textToType)
            val cursor = start + textToType.length
            current.copy(
                draftText = updatedText,
                draftSelectionStart = cursor.coerceIn(0, updatedText.length),
                draftSelectionEnd = cursor.coerceIn(0, updatedText.length),
                draftRevision = current.draftRevision + 1,
                statusLine = latestWords,
            )
        }
        alreadyTypedTranscript = latestWords
    }

    private fun rememberTranscript(words: String, didInsertIntoFocusedText: Boolean) {
        val spokenAtEpochMilliseconds = System.currentTimeMillis()
        val entry = DictationTranscriptEntry(
            id = nextTranscriptId.getAndIncrement(),
            text = words,
            spokenAtLabel = spokenAtLabel(spokenAtEpochMilliseconds),
            spokenAtEpochMilliseconds = spokenAtEpochMilliseconds,
            didInsertIntoFocusedText = didInsertIntoFocusedText,
        )
        updateScreenState { current ->
            current.copy(
                transcriptHistory = transcriptsStillWithinSevenDays(listOf(entry) + current.transcriptHistory),
            )
        }
        saveSettings()
    }

    private fun startClock() {
        stopClock()
        clockTask = clock.scheduleAtFixedRate(
            {
                val elapsed = System.currentTimeMillis() - listeningStartedAtMillis
                val level = microphone.microphoneLevel
                val seconds = (elapsed / 1000).toInt()
                val clockLabel = "%d:%02d".format(seconds / 60, seconds % 60)
                updateScreenState { current ->
                    current.copy(
                        listeningElapsedMilliseconds = elapsed,
                        microphoneLevel = level,
                    )
                }
                if (sessionPhase == DictationSessionPhase.Listening) {
                    hotkeyBridge.showPill(
                        title = "Listening",
                        detail = clockLabel,
                        tone = "listening",
                        microphoneLevel = level,
                    )
                    val now = System.currentTimeMillis()
                    if (elapsed >= 900 && now - lastLiveTranscriptionAtMillis >= 1_400 && !isLiveTranscriptionQueued) {
                        lastLiveTranscriptionAtMillis = now
                        isLiveTranscriptionQueued = true
                        sessionThread.execute {
                            try {
                                transcribeWhatHasBeenSaidSoFar()
                            } finally {
                                isLiveTranscriptionQueued = false
                            }
                        }
                    }
                }
                if (elapsed >= longestTakeInMilliseconds && !didScheduleAutoStop) {
                    didScheduleAutoStop = true
                    sessionThread.execute {
                        finishListening()
                    }
                }
            },
            0,
            200,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    private fun stopClock() {
        clockTask?.cancel(false)
        clockTask = null
    }

    private fun schedulePillHide() {
        clock.schedule(
            {
                if (sessionPhase == DictationSessionPhase.Idle) {
                    hotkeyBridge.hidePill()
                }
            },
            1600,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    private fun prepareMicrophone() {
        refreshMicrophones()
    }

    private fun prepareSpeechModel() {
        val llamaServerBinary = findLlamaServerBinary()
        if (llamaServerBinary == null) {
            engineIsReady = false
            updateScreenState { current ->
                current.copy(
                    enginePhase = DictationEnginePhase.MissingRuntime,
                    engineDetail = "llama.cpp is not installed. Install it with: brew install llama.cpp",
                )
            }
            return
        }
        if (!modelFile.isFile() || !audioProjectorFile.isFile()) {
            engineIsReady = false
            updateScreenState { current ->
                current.copy(
                    enginePhase = DictationEnginePhase.MissingModel,
                    engineDetail = "The model files are not in ${modelDirectory.path}. Download LFM3-Audio-2B-Q8_0.gguf and mmproj-LFM3-Audio-2B-Q8_0.gguf from LiquidAI/LFM3-Audio-2B-GGUF.",
                )
            }
            return
        }
        val speechTranscriber = LlamaServerSpeechTranscriber(
            llamaServerBinary = llamaServerBinary,
            modelFile = modelFile,
            audioProjectorFile = audioProjectorFile,
            logFile = File(runtimeDirectory, "llama-server.log"),
        )
        transcriber = speechTranscriber
        val failure = speechTranscriber.startAndWaitUntilReady()
        if (failure != null) {
            engineIsReady = false
            updateScreenState { current ->
                current.copy(
                    enginePhase = DictationEnginePhase.Failed,
                    engineDetail = failure,
                )
            }
            return
        }
        engineIsReady = true
        prepareSpeakingVoice()
        updateScreenState { current ->
            current.copy(
                enginePhase = DictationEnginePhase.Ready,
                engineDetail = "LFM3-Audio-2B is loaded on this Mac.",
                statusLine = if (current.isGlobalHotkeyRegistered) {
                    describeDictationShortcut(current.shortcutLabel)
                } else {
                    current.statusLine
                },
            )
        }
    }

    private fun loadSettings() {
        if (!settingsFile.isFile()) {
            return
        }
        val saved = try {
            JSONObject(settingsFile.readText())
        } catch (_: Exception) {
            return
        }
        val history = mutableListOf<DictationTranscriptEntry>()
        val savedHistory = saved.optJSONArray("history") ?: JSONArray()
        val loadedAtEpochMilliseconds = System.currentTimeMillis()
        for (index in 0 until savedHistory.length()) {
            val item = savedHistory.optJSONObject(index) ?: continue
            val spokenAtEpochMilliseconds = if (item.has("spokenAtEpochMilliseconds")) {
                item.getLong("spokenAtEpochMilliseconds")
            } else {
                loadedAtEpochMilliseconds
            }
            history.add(
                DictationTranscriptEntry(
                    id = item.optLong("id", nextTranscriptId.getAndIncrement()),
                    text = item.optString("text"),
                    spokenAtLabel = item.optString("spokenAtLabel"),
                    spokenAtEpochMilliseconds = spokenAtEpochMilliseconds,
                    didInsertIntoFocusedText = item.optBoolean("didInsertIntoFocusedText"),
                ),
            )
        }
        val keptHistory = transcriptsStillWithinSevenDays(history)
        val highestId = history.maxOfOrNull { entry -> entry.id } ?: 0L
        nextTranscriptId.set(highestId + 1)
        updateScreenState { current ->
            current.copy(
                selectedLanguageEnglishName = saved.optString(
                    "languageEnglishName",
                    current.selectedLanguageEnglishName,
                ),
                shortcutKeyCode = saved.optInt("shortcutKeyCode", current.shortcutKeyCode),
                shortcutModifiers = saved.optInt("shortcutModifiers", current.shortcutModifiers),
                shortcutLabel = saved.optString("shortcutLabel", current.shortcutLabel),
                openAtLogin = macLoginItem.isEnabled(),
                appearanceMode = saved.optString("appearanceMode", current.appearanceMode),
                selectedMicrophoneId = saved.optString("selectedMicrophoneId", current.selectedMicrophoneId),
                selectedMicrophoneName = saved.optString("selectedMicrophoneName", current.selectedMicrophoneName),
                transcriptHistory = keptHistory,
                wordReplacements = wordReplacementsFromSettings(saved).fold(emptyList()) { mergedWordReplacements, savedWordReplacement ->
                    addOrMergeWordReplacement(mergedWordReplacements, savedWordReplacement.misheardSpellings, savedWordReplacement.correctWord)
                },
                selectedSpeakingVoiceId = saved.optString("speakingVoiceId", current.selectedSpeakingVoiceId),
                speakingSpeed = saved.optDouble("speakingSpeed", current.speakingSpeed.toDouble()).toFloat().takeIf { it in speakingSpeeds } ?: 1f,
            )
        }
        if (keptHistory.size < history.size || !saved.has("wordReplacements")) {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val snapshot = screenState
        synchronized(settingsLock) {
            val history = JSONArray()
            transcriptsStillWithinSevenDays(snapshot.transcriptHistory).forEach { entry ->
                history.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("text", entry.text)
                        .put("spokenAtLabel", entry.spokenAtLabel)
                        .put("spokenAtEpochMilliseconds", entry.spokenAtEpochMilliseconds)
                        .put("didInsertIntoFocusedText", entry.didInsertIntoFocusedText),
                )
            }
            val saved = JSONObject()
            saved.put("languageEnglishName", snapshot.selectedLanguageEnglishName)
            saved.put("shortcutKeyCode", snapshot.shortcutKeyCode)
            saved.put("shortcutModifiers", snapshot.shortcutModifiers)
            saved.put("shortcutLabel", snapshot.shortcutLabel)
            saved.put("openAtLogin", snapshot.openAtLogin)
            saved.put("appearanceMode", snapshot.appearanceMode)
            saved.put("selectedMicrophoneId", snapshot.selectedMicrophoneId)
            saved.put("selectedMicrophoneName", snapshot.selectedMicrophoneName)
            saved.put("history", history)
            val wordReplacements = JSONArray()
            snapshot.wordReplacements.forEach { wordReplacement ->
                wordReplacements.put(
                    JSONObject()
                        .put("id", wordReplacement.id)
                        .put("misheardSpellings", JSONArray(wordReplacement.misheardSpellings))
                        .put("correctWord", wordReplacement.correctWord),
                )
            }
            saved.put("wordReplacements", wordReplacements)
            saved.put("speakingVoiceId", snapshot.selectedSpeakingVoiceId)
            saved.put("speakingSpeed", snapshot.speakingSpeed.toDouble())
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(saved.toString(2))
        }
    }

    /**
     * Reads the saved word fixes. Settings from before this feature have none saved, so they start with the Peekopals fix the feature was added for.
     */
    private fun wordReplacementsFromSettings(saved: JSONObject): List<WordReplacement> {
        val savedWordReplacements = saved.optJSONArray("wordReplacements") ?: return listOf(
            WordReplacement(id = 1, misheardSpellings = listOf("Peacopals", "Peako pals"), correctWord = "Peekopals"),
        )
        val wordReplacements = mutableListOf<WordReplacement>()
        for (index in 0 until savedWordReplacements.length()) {
            val item = savedWordReplacements.optJSONObject(index) ?: continue
            val savedSpellings = item.optJSONArray("misheardSpellings") ?: JSONArray()
            val misheardSpellings = (0 until savedSpellings.length()).map { spellingIndex ->
                savedSpellings.optString(spellingIndex)
            }.filter {
                it.isNotBlank()
            }
            val correctWord = item.optString("correctWord").trim()
            if (misheardSpellings.isNotEmpty() && correctWord.isNotEmpty()) {
                wordReplacements.add(
                    WordReplacement(id = item.optLong("id", index + 1L), misheardSpellings = misheardSpellings, correctWord = correctWord),
                )
            }
        }
        return wordReplacements
    }

    /**
     * Drops dictations spoken more than seven days ago. Older saved entries that have no timestamp are kept and dated from this launch, because their original day is unknown.
     */
    private fun transcriptsStillWithinSevenDays(
        transcriptEntries: List<DictationTranscriptEntry>,
    ): List<DictationTranscriptEntry> {
        val nowEpochMilliseconds = System.currentTimeMillis()
        return transcriptEntries.filter { transcriptEntry ->
            nowEpochMilliseconds - transcriptEntry.spokenAtEpochMilliseconds < sevenDaysInMilliseconds
        }
    }

    private fun spokenAtLabel(spokenAtEpochMilliseconds: Long): String {
        val spokenAt = Instant.ofEpochMilli(spokenAtEpochMilliseconds).atZone(ZoneId.systemDefault())
        val timeLabel = DateTimeFormatter.ofPattern("h:mm a").format(spokenAt)
        if (spokenAt.toLocalDate() == LocalDate.now()) {
            return timeLabel
        }
        val dateLabel = DateTimeFormatter.ofPattern("MMM d").format(spokenAt)
        return "$dateLabel, $timeLabel"
    }

    private fun updateScreenState(change: (DictationScreenState) -> DictationScreenState) {
        val applyChange = Runnable {
            screenState = change(screenState)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            applyChange.run()
            return
        }
        try {
            SwingUtilities.invokeAndWait(applyChange)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (exception: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(exception.targetException)
        }
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    private fun ensureHelperBinaryIsBuilt() {
        val sourceFile = File(projectRoot, "native/macos/GlobalDictationHelper.swift")
        val infoPlistFile = File(projectRoot, "native/macos/HelperInfo.plist")
        val launcherSourceFile = File(projectRoot, "native/macos/launch-dictation-helper.c")
        val helperExecutable = helperAppExecutable()
        val launcherBinary = helperLauncherBinary()
        val helperNeedsCompile = !helperExecutable.canExecute() ||
            helperExecutable.lastModified() < sourceFile.lastModified() ||
            helperExecutable.lastModified() < infoPlistFile.lastModified()
        if (helperNeedsCompile) {
            helperExecutable.parentFile?.mkdirs()
            val bundledInfoPlist = File(helperAppBundle(), "Contents/Info.plist")
            bundledInfoPlist.parentFile?.mkdirs()
            infoPlistFile.copyTo(bundledInfoPlist, overwrite = true)
            val compile = ProcessBuilder(
                "swiftc",
                "-parse-as-library",
                "-O",
                "-o",
                helperExecutable.absolutePath,
                sourceFile.absolutePath,
                "-framework",
                "Cocoa",
                "-framework",
                "Carbon",
                "-framework",
                "ApplicationServices",
                "-framework",
                "AVFoundation",
            ).redirectErrorStream(true).start()
            val compileOutput = compile.inputStream.readBytes().decodeToString()
            if (compile.waitFor() != 0) {
                updateScreenState { current ->
                    current.copy(statusLine = "Could not build the shortcut helper. $compileOutput")
                }
                return
            }
        }
        val launcherNeedsCompile = !launcherBinary.canExecute() ||
            launcherBinary.lastModified() < launcherSourceFile.lastModified()
        if (launcherNeedsCompile) {
            val compileLauncher = ProcessBuilder(
                "cc",
                "-O2",
                "-o",
                launcherBinary.absolutePath,
                launcherSourceFile.absolutePath,
            ).redirectErrorStream(true).start()
            val compileOutput = compileLauncher.inputStream.readBytes().decodeToString()
            if (compileLauncher.waitFor() != 0) {
                updateScreenState { current ->
                    current.copy(statusLine = "Could not build the shortcut launcher. $compileOutput")
                }
                return
            }
        }
        if (!isHelperSigned(helperAppBundle())) {
            signHelper(helperAppBundle())
        }
        if (!isHelperSigned(launcherBinary)) {
            signHelper(launcherBinary)
        }
    }

    private fun isHelperSigned(binaryFile: File): Boolean {
        val verify = ProcessBuilder(
            "codesign",
            "--verify",
            "--strict",
            binaryFile.absolutePath,
        ).redirectErrorStream(true).start()
        verify.inputStream.readBytes()
        return verify.waitFor() == 0
    }

    private fun signHelper(binaryFile: File) {
        ProcessBuilder(
            "codesign",
            "-s",
            "-",
            "--force",
            "--identifier",
            "com.reaptivevoice.dictation-helper",
            binaryFile.absolutePath,
        ).start().waitFor()
    }

    private fun helperAppBundle(): File {
        return File(projectRoot, "native/macos/Rvoice.app")
    }

    private fun helperAppExecutable(): File {
        return File(helperAppBundle(), "Contents/MacOS/ReaptiveVoiceDictationHelper")
    }

    private fun helperLauncherBinary(): File {
        return File(projectRoot, "native/macos/ReaptiveVoiceLauncher")
    }
}

private fun packagedOrProjectFile(relativePath: String): File {
    val resourcesDirectory = System.getProperty("compose.application.resources.dir")
    if (resourcesDirectory != null) {
        val packagedFile = File(resourcesDirectory, relativePath)
        if (packagedFile.exists()) {
            return packagedFile
        }
    }
    val projectFile = File(findProjectRoot(), "native/macos/$relativePath")
    if (relativePath.startsWith("RvoiceHelper.app")) {
        return File(findProjectRoot(), "native/macos/Rvoice.app/Contents/MacOS/ReaptiveVoiceDictationHelper")
    }
    return projectFile
}

private fun findProjectRoot(): File {
    var directory: File? = File(System.getProperty("user.dir")).absoluteFile
    var remainingHops = 6
    while (directory != null && remainingHops > 0) {
        if (File(directory, "settings.gradle.kts").isFile) {
            return directory
        }
        directory = directory.parentFile
        remainingHops -= 1
    }
    val home = File(System.getProperty("user.home"))
    val knownProjects = listOf(
        File("/Users/anudeepmac/IntelliJ/Reaptive-Voice"),
        File(home, "IntelliJ/Reaptive-Voice"),
    )
    return knownProjects.firstOrNull { project ->
        File(project, "models/LFM3-Audio-2B/LFM3-Audio-2B-Q8_0.gguf").isFile
    } ?: File(System.getProperty("user.dir"))
}

private fun findLlamaServerBinary(): File? {
    val candidates = listOf(
        File("/opt/homebrew/bin/llama-server"),
        File("/usr/local/bin/llama-server"),
    )
    return candidates.firstOrNull { candidate ->
        candidate.canExecute()
    }
}
