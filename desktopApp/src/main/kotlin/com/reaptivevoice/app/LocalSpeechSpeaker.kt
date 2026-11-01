package com.reaptivevoice.app

import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter

/**
 * Runs Rvoice's speaking program (`RvoiceSpeaker`), which keeps LFM3-Audio-2B loaded and reads text out loud through the speakers as it is generated. Loading takes a few seconds once; after that speech starts in well under a second.
 */
class LocalSpeechSpeaker(
    private val speakerBinary: File,
    private val modelFile: File,
    private val audioProjectorFile: File,
    private val logFile: File,
) {
    private var speakerProcess: Process? = null
    private var commandWriter: OutputStreamWriter? = null
    private val writerLock = Any()

    @Volatile
    var isReady = false
        private set

    var onReady: () -> Unit = {}
    var onStartedSpeaking: () -> Unit = {}
    var onLoudness: (Float) -> Unit = {}
    var onPausedOrResumed: (isPaused: Boolean) -> Unit = {}
    var onFinishedSpeaking: (wasStopped: Boolean) -> Unit = {}
    var onRecordedWhatWasSpoken: (wavFile: File) -> Unit = {}
    var onSaveProgress: (finishedPartCount: Int, totalPartCount: Int) -> Unit = { _, _ -> }
    var onSaved: (wavFile: File, lengthInSeconds: Double) -> Unit = { _, _ -> }
    var onSaveFailed: (String) -> Unit = {}
    var onSaveCancelled: () -> Unit = {}
    var onBusy: (String) -> Unit = {}
    var onProblem: (String) -> Unit = {}

    /** Runs when the speaking program has quit, unless `shutdown` was called. */
    var onSpeakerProgramQuit: () -> Unit = {}

    @Volatile
    private var isShuttingDown = false

    /**
     * Starts the speaking program and returns at once; `onReady` runs when the model is loaded. Returns a reason when the program cannot be started, otherwise null.
     */
    fun startLoadingSpeechModel(): String? {
        if (!speakerBinary.canExecute()) {
            return "The speaking program is missing at ${speakerBinary.path}."
        }
        logFile.parentFile?.mkdirs()
        val process = ProcessBuilder(speakerBinary.absolutePath, modelFile.absolutePath, audioProjectorFile.absolutePath)
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        speakerProcess = process
        commandWriter = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        Thread(
            {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        handleSpeakerMessage(line)
                    }
                }
                isReady = false
                if (!isShuttingDown) {
                    onSpeakerProgramQuit()
                }
            },
            "speech-speaker-output",
        ).apply {
            isDaemon = true
            start()
        }
        return null
    }

    /**
     * Reads the text out loud, cutting off anything already being spoken, and writes what was spoken to `recordingWavFile` when done. Line breaks become spaces, because each command is one line.
     */
    fun speakTextOutLoud(text: String, recordingWavFile: File) {
        val textOnOneLine = textAsOneLine(text)
        if (textOnOneLine.isEmpty()) {
            return
        }
        sendCommandToSpeaker("speak ${recordingWavFile.absolutePath}\t$textOnOneLine")
    }

    /**
     * Turns the text into a WAV file without playing it. Progress arrives through `onSaveProgress`, the result through `onSaved`, `onSaveFailed` or `onSaveCancelled`.
     */
    fun saveTextToAudioFile(text: String, wavFile: File) {
        val textOnOneLine = textAsOneLine(text)
        if (textOnOneLine.isEmpty()) {
            return
        }
        sendCommandToSpeaker("save ${wavFile.absolutePath}\t$textOnOneLine")
    }

    fun cancelSavingAudioFile() {
        sendCommandToSpeaker("cancel-save")
    }

    /** Playback speed from 0.5 to 2; the pitch stays the same. Applies at once, also mid-sentence. */
    fun setPlaybackSpeed(speed: Float) {
        sendCommandToSpeaker("speed $speed")
    }

    private fun textAsOneLine(text: String): String {
        return text.replace(Regex("[\\r\\n\\t]+"), " ").trim()
    }

    /**
     * Chooses the voice for the next text read out loud. Safe to call before the model has loaded; the program reads it once it is ready.
     */
    fun chooseVoice(voice: SpeakingVoice) {
        sendCommandToSpeaker("style ${voice.languageCode}\t${voice.promptLanguageName}\t${voice.gender}")
    }

    fun pauseSpeaking() {
        sendCommandToSpeaker("pause")
    }

    fun resumeSpeaking() {
        sendCommandToSpeaker("resume")
    }

    fun jumpBackWhileSpeaking(seconds: Int) {
        sendCommandToSpeaker("rewind $seconds")
    }

    fun stopSpeaking() {
        sendCommandToSpeaker("stop")
    }

    fun shutdown() {
        isShuttingDown = true
        sendCommandToSpeaker("quit")
        speakerProcess?.destroy()
        speakerProcess = null
        isReady = false
    }

    private fun sendCommandToSpeaker(commandLine: String) {
        val writer = commandWriter ?: return
        synchronized(writerLock) {
            try {
                writer.write(commandLine)
                writer.write("\n")
                writer.flush()
            } catch (_: Exception) {
                isReady = false
            }
        }
    }

    private fun handleSpeakerMessage(line: String) {
        val message = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        when (message.optString("type")) {
            "ready" -> {
                isReady = true
                onReady()
            }
            "started" -> onStartedSpeaking()
            "level" -> onLoudness(message.optDouble("level", 0.0).toFloat())
            "paused" -> onPausedOrResumed(true)
            "resumed" -> onPausedOrResumed(false)
            "finished" -> onFinishedSpeaking(message.optBoolean("stopped"))
            "recorded" -> onRecordedWhatWasSpoken(File(message.optString("path")))
            "save-progress" -> onSaveProgress(message.optInt("done"), message.optInt("total"))
            "saved" -> onSaved(File(message.optString("path")), message.optDouble("seconds", 0.0))
            "save-failed" -> onSaveFailed(message.optString("message"))
            "save-cancelled" -> onSaveCancelled()
            "busy" -> onBusy(message.optString("message"))
            "error" -> onProblem(message.optString("message"))
        }
    }
}
