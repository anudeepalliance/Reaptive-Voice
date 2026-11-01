package com.reaptivevoice.app

import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records through a small Mac program that macOS can recognize as Rvoice. Recording inside the Java process comes out silent, because macOS treats that process as IntelliJ.
 */
class MicrophonePcmRecorder(
    private val launcherBinary: File,
    private val recorderBinary: File,
    private val captureFile: File,
    private val logFile: File,
) {
    @Volatile
    var microphoneLevel: Float = 0f
        private set

    private var recorderProcess: Process? = null
    private var commandWriter: OutputStreamWriter? = null
    private val controlLines = LinkedBlockingQueue<String>()

    fun listInputDevices(): List<MicrophoneChoice> {
        if (!launcherBinary.canExecute() || !recorderBinary.canExecute()) {
            return emptyList()
        }
        return try {
            logFile.parentFile?.mkdirs()
            val process = ProcessBuilder(
                launcherBinary.absolutePath,
                recorderBinary.absolutePath,
                "--list",
            ).redirectError(ProcessBuilder.Redirect.appendTo(logFile)).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.waitFor(5, TimeUnit.SECONDS)
            val deviceLine = output.lineSequence().firstOrNull { line ->
                line.contains("\"devices\"")
            } ?: return emptyList()
            val devices = JSONObject(deviceLine).optJSONArray("devices") ?: return emptyList()
            val choices = mutableListOf<MicrophoneChoice>()
            for (index in 0 until devices.length()) {
                val device = devices.optJSONObject(index) ?: continue
                val identifier = device.optString("id")
                val name = device.optString("name")
                if (identifier.isNotBlank() && name.isNotBlank()) {
                    choices.add(MicrophoneChoice(id = identifier, name = name))
                }
            }
            choices
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun start(selectedMicrophoneId: String): String? {
        if (!launcherBinary.canExecute() || !recorderBinary.canExecute()) {
            return "The microphone recorder is missing. Start Rvoice again from the project."
        }
        stopProcess()
        captureFile.parentFile?.mkdirs()
        logFile.parentFile?.mkdirs()
        val command = mutableListOf(
            launcherBinary.absolutePath,
            recorderBinary.absolutePath,
            captureFile.absolutePath,
        )
        if (selectedMicrophoneId.isNotBlank()) {
            command.add(selectedMicrophoneId)
        }
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(logFile)).start()
        recorderProcess = process
        commandWriter = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        Thread(
            {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        handleRecorderLine(line)
                    }
                }
            },
            "microphone-recorder",
        ).apply {
            isDaemon = true
            start()
        }
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive && controlLines.isEmpty()) {
                return "The microphone recorder stopped before it started listening."
            }
            val line = controlLines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val message = JSONObject(line)
            when (message.optString("type")) {
                "started" -> return null
                "error" -> {
                    stopProcess()
                    return message.optString("message").ifBlank { "The microphone could not start." }
                }
            }
        }
        stopProcess()
        return "The microphone is not available. macOS does not let you add an app on that screen. If Rvoice is listed, turn it on. Otherwise click the shortcut again and allow the prompt."
    }

    /**
     * Stops the microphone and writes the wav. A returned string is the reason it was not saved.
     */
    fun stopAndWriteWav(destination: File): String? {
        val writer = commandWriter
        val process = recorderProcess
        if (writer == null || process == null) {
            return "The microphone never started."
        }
        writer.write("stop\n")
        writer.flush()
        val stoppedLine = nextControlLine(wantedType = "stopped", timeoutMilliseconds = 8_000)
        process.waitFor(2, TimeUnit.SECONDS)
        stopProcess()
        if (stoppedLine == null) {
            return "The recording did not finish."
        }
        val message = JSONObject(stoppedLine)
        if (message.optString("type") == "error") {
            return message.optString("message").ifBlank { "The microphone could not record." }
        }
        if (!captureFile.isFile || captureFile.length() < 44 + 8_000) {
            return "That was too short. Speak a little longer, then press the shortcut again."
        }
        val peakDecibels = message.optDouble("peakDecibels", -160.0)
        if (peakDecibels < -55.0) {
            return "The microphone was silent. Choose another microphone in the list. If Rvoice is in Microphone settings, turn it on."
        }
        if (destination.absolutePath != captureFile.absolutePath) {
            captureFile.copyTo(destination, overwrite = true)
        }
        return null
    }

    /**
     * Opens the microphone once at launch so the permission prompt appears before the first shortcut.
     */
    /**
     * Writes the audio recorded so far without stopping. Returns a copy the speech model can read while recording continues.
     */
    fun snapshotForLiveTranscription(): File? {
        val writer = commandWriter ?: return null
        writer.write("snapshot\n")
        writer.flush()
        val snapshotLine = nextControlLine(wantedType = "snapshot", timeoutMilliseconds = 2_000) ?: return null
        val sampleCount = JSONObject(snapshotLine).optInt("samples")
        if (sampleCount < 8_000 || !captureFile.isFile) {
            return null
        }
        val partialFile = File(captureFile.parentFile, "partial-dictation.wav")
        captureFile.copyTo(partialFile, overwrite = true)
        return partialFile
    }

    fun requestPermissionOnce(selectedMicrophoneId: String): String? {
        val problem = start(selectedMicrophoneId)
        if (problem != null) {
            return problem
        }
        Thread.sleep(150)
        commandWriter?.write("stop\n")
        commandWriter?.flush()
        controlLines.poll(5, TimeUnit.SECONDS)
        stopProcess()
        return null
    }

    private fun nextControlLine(wantedType: String, timeoutMilliseconds: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val line = controlLines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (JSONObject(line).optString("type") == wantedType) {
                return line
            }
        }
        return null
    }

    private fun handleRecorderLine(line: String) {
        val message = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        if (message.optString("type") == "level") {
            microphoneLevel = message.optDouble("level", 0.0).toFloat().coerceIn(0f, 1f)
            return
        }
        controlLines.offer(line)
    }

    private fun stopProcess() {
        commandWriter = null
        recorderProcess?.destroy()
        recorderProcess = null
        controlLines.clear()
        microphoneLevel = 0f
    }
}
