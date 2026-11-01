package com.reaptivevoice.app

import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What happened when the helper tried to put the transcript into the focused text field.
 */
data class FocusedTextInsertOutcome(
    val didInsert: Boolean,
    val method: String,
    val message: String,
)

/**
 * Talks to the small Mac helper that owns the ⌘D shortcut. The helper has to be a real Mac process so it can see the focused text field in other apps and type into it. Compose cannot do that on its own.
 */
class GlobalDictationHotkeyBridge(
    private val launcherBinary: File,
    private val helperExecutable: File,
    private val logFile: File,
) {
    private var helperProcess: Process? = null
    private var commandWriter: OutputStreamWriter? = null
    private val writerLock = Any()
    private val insertLock = Any()
    private var pendingInsert: ((FocusedTextInsertOutcome) -> Unit)? = null

    var onHotkey: (phase: String, focusCaptured: Boolean) -> Unit = { _, _ -> }

    /** Runs on the helper's reading thread the moment Escape cancels a take, before the queued cancel work runs. */
    var onHotkeyCancelRequestedImmediately: () -> Unit = { }
    var onShortcutRegistered: (registered: Boolean, error: String) -> Unit = { _, _ -> }
    var onReady: (accessibilityTrusted: Boolean, hotkeyRegistered: Boolean, hotkeyError: String) -> Unit = { _, _, _ -> }
    var onAccessibilityChanged: (Boolean) -> Unit = { }
    var onSpeakRequest: (text: String) -> Unit = { }
    var onStopSpeakingRequest: () -> Unit = { }
    var onSpeechControl: (action: String) -> Unit = { }
    var onSaveAudioRequest: (text: String) -> Unit = { }
    var onSavedAudioPlayerState: (SavedAudioPlayerState) -> Unit = { }
    var onSavedAudioPlayerClosed: () -> Unit = { }
    var onSavedAudioPlayerProblem: (String) -> Unit = { }

    fun start() {
        if (!launcherBinary.canExecute() || !helperExecutable.canExecute()) {
            onReady(false, false, "The shortcut helper is missing at ${helperExecutable.path}.")
            return
        }
        logFile.parentFile?.mkdirs()
        val processBuilder = ProcessBuilder(launcherBinary.absolutePath, helperExecutable.absolutePath)
        processBuilder.environment()["REAPTIVE_VOICE_PARENT_PID"] = ProcessHandle.current().pid().toString()
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        val process = processBuilder.start()
        helperProcess = process
        commandWriter = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        Thread(
            {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        handleHelperLine(line)
                    }
                }
            },
            "dictation-helper-output",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun showPill(title: String, detail: String, tone: String, microphoneLevel: Float) {
        val command = JSONObject()
        command.put("command", "pill")
        command.put("title", title)
        command.put("detail", detail)
        command.put("tone", tone)
        command.put("level", microphoneLevel.toDouble())
        send(command)
    }

    fun hidePill() {
        val command = JSONObject()
        command.put("command", "pill")
        command.put("hide", true)
        send(command)
    }

    fun setShortcut(keyCode: Int, modifiers: Int) {
        val command = JSONObject()
        command.put("command", "set-hotkey")
        command.put("keyCode", keyCode)
        command.put("modifiers", modifiers)
        send(command)
    }

    fun setIdle() {
        val command = JSONObject()
        command.put("command", "set-mode")
        command.put("mode", "idle")
        send(command)
    }

    /** Opens an MP3 in the helper's built-in player and starts playing it. Progress arrives through `onSavedAudioPlayerState`. */
    fun openSavedAudioInPlayer(filePath: String) {
        send(JSONObject().put("command", "player-open").put("path", filePath).put("speed", 1.0))
    }

    fun toggleSavedAudioPlayback() {
        send(JSONObject().put("command", "player-toggle"))
    }

    /** Jumps by the given number of seconds; negative goes back. */
    fun skipSavedAudioPlayback(seconds: Int) {
        send(JSONObject().put("command", "player-skip").put("seconds", seconds))
    }

    fun seekSavedAudioPlayback(positionInSeconds: Double) {
        send(JSONObject().put("command", "player-seek").put("seconds", positionInSeconds))
    }

    fun closeSavedAudioPlayer() {
        send(JSONObject().put("command", "player-close"))
    }

    fun promptForAccessibility() {
        send(JSONObject().put("command", "prompt-accessibility"))
    }

    /**
     * Types the take's latest text into its field. `isFinalText` marks the last update of a take: only that one is delivered to the take's field after the person has moved to another app.
     */
    fun insertTranscript(text: String, isFinalText: Boolean): FocusedTextInsertOutcome {
        val latch = CountDownLatch(1)
        var outcome = FocusedTextInsertOutcome(
            didInsert = false,
            method = "none",
            message = "The shortcut helper did not answer.",
        )
        synchronized(insertLock) {
            pendingInsert = { result ->
                outcome = result
                latch.countDown()
            }
        }
        val command = JSONObject()
        command.put("command", "insert")
        command.put("text", text)
        command.put("final", isFinalText)
        send(command)
        latch.await(8, TimeUnit.SECONDS)
        synchronized(insertLock) {
            pendingInsert = null
        }
        return outcome
    }

    fun shutdown() {
        helperProcess?.destroy()
        helperProcess = null
    }

    private fun send(command: JSONObject) {
        val writer = commandWriter ?: return
        synchronized(writerLock) {
            writer.write(command.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    private fun handleHelperLine(line: String) {
        val message = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        when (message.optString("type")) {
            "ready" -> {
                onReady(
                    message.optBoolean("accessibilityTrusted"),
                    message.optBoolean("hotkeyRegistered"),
                    message.optString("hotkeyError"),
                )
            }
            "accessibility" -> {
                onAccessibilityChanged(message.optBoolean("trusted"))
            }
            "hotkey" -> {
                if (message.optString("phase") == "cancel") {
                    onHotkeyCancelRequestedImmediately()
                }
                onHotkey(message.optString("phase"), message.optBoolean("focusCaptured"))
            }
            "speak-request" -> {
                onSpeakRequest(message.optString("text"))
            }
            "stop-speaking" -> {
                onStopSpeakingRequest()
            }
            "player-state" -> {
                onSavedAudioPlayerState(
                    SavedAudioPlayerState(
                        filePath = message.optString("path"),
                        title = "",
                        positionInSeconds = message.optDouble("position", 0.0),
                        lengthInSeconds = message.optDouble("length", 0.0),
                        isPlaying = message.optBoolean("playing"),
                    ),
                )
            }
            "player-closed" -> {
                onSavedAudioPlayerClosed()
            }
            "player-error" -> {
                onSavedAudioPlayerProblem(message.optString("message"))
            }
            "save-audio-request" -> {
                onSaveAudioRequest(message.optString("text"))
            }
            "speech-control" -> {
                onSpeechControl(message.optString("action"))
            }
            "shortcut" -> {
                onShortcutRegistered(message.optBoolean("registered"), message.optString("error"))
            }
            "inserted" -> {
                val outcome = FocusedTextInsertOutcome(
                    didInsert = message.optBoolean("ok"),
                    method = message.optString("method"),
                    message = message.optString("message"),
                )
                val callback = synchronized(insertLock) { pendingInsert }
                callback?.invoke(outcome)
            }
        }
    }
}
