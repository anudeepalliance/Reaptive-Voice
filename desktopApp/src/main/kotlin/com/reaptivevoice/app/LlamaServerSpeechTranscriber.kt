package com.reaptivevoice.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Keeps one local llama.cpp server loaded with LFM3-Audio-2B, and sends each recorded wav to it for speech-to-text. The server stays running so the second sentence does not pay the model load again.
 */
class LlamaServerSpeechTranscriber(
    private val llamaServerBinary: File,
    private val modelFile: File,
    private val audioProjectorFile: File,
    private val logFile: File,
    private val port: Int = 8741,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private var serverProcess: Process? = null

    fun startAndWaitUntilReady(): String? {
        if (!llamaServerBinary.canExecute()) {
            return "llama-server was not found at ${llamaServerBinary.path}."
        }
        if (!modelFile.isFile() || !audioProjectorFile.isFile()) {
            return "The LFM3-Audio-2B weight files are missing."
        }
        logFile.parentFile?.mkdirs()
        serverProcess = launchServer(includeReasoningOff = true)
        val firstFailure = waitUntilHealthy(serverProcess!!)
        if (firstFailure == null) {
            return null
        }
        val logText = logTail()
        if (!logText.contains("reasoning", ignoreCase = true)) {
            return firstFailure
        }
        serverProcess?.destroy()
        serverProcess = launchServer(includeReasoningOff = false)
        return waitUntilHealthy(serverProcess!!)
    }

    fun transcribeWavFile(wavFile: File, languageEnglishName: String): String {
        val wavBytes = wavFile.readBytes()
        val body = JSONObject()
        body.put("model", "lfm3-audio")
        body.put("temperature", 0)
        body.put("stream", false)
        body.put("max_tokens", 1024)
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", buildAsrSystemPrompt(languageEnglishName)),
        )
        val audioPart = JSONObject()
            .put("type", "input_audio")
            .put(
                "input_audio",
                JSONObject()
                    .put("data", Base64.getEncoder().encodeToString(wavBytes))
                    .put("format", "wav"),
            )
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(audioPart)),
        )
        body.put("messages", messages)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/v1/chat/completions"))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException(errorMessageFromServer(response.body()))
        }
        return cleanModelTranscript(transcriptFromChatCompletion(response.body()))
    }

    fun shutdown() {
        serverProcess?.destroy()
        serverProcess = null
    }

    private fun launchServer(includeReasoningOff: Boolean): Process {
        val command = mutableListOf(
            llamaServerBinary.absolutePath,
            "-m", modelFile.absolutePath,
            "--mmproj", audioProjectorFile.absolutePath,
            "-c", "16384",
            "-ngl", "all",
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--no-webui",
            "-a", "lfm3-audio",
            "--temp", "0",
            "--parallel", "1",
            "-np", "1",
            "--jinja",
        )
        if (includeReasoningOff) {
            command.add("--reasoning")
            command.add("off")
        }
        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(true)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        return processBuilder.start()
    }

    private fun waitUntilHealthy(process: Process): String? {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                return "The speech model stopped while loading. ${logTail()}"
            }
            if (isHealthy()) {
                return null
            }
            Thread.sleep(400)
        }
        return "The speech model did not become ready in time. ${logTail()}"
    }

    private fun isHealthy(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun logTail(): String {
        if (!logFile.isFile()) {
            return ""
        }
        return logFile.readText()
            .lineSequence()
            .filter { line ->
                line.isNotBlank()
            }
            .toList()
            .takeLast(6)
            .joinToString(" ")
            .take(500)
    }
}

private fun errorMessageFromServer(responseBody: String): String {
    return try {
        JSONObject(responseBody).getJSONObject("error").optString("message", responseBody.take(400))
    } catch (_: Exception) {
        responseBody.take(400)
    }
}

private fun transcriptFromChatCompletion(responseBody: String): String {
    val message = JSONObject(responseBody)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
    val content = message.get("content")
    if (content is String) {
        return content
    }
    if (content is JSONArray) {
        val textParts = StringBuilder()
        for (index in 0 until content.length()) {
            val part = content.get(index)
            if (part is JSONObject) {
                textParts.append(part.optString("text"))
            } else if (part is String) {
                textParts.append(part)
            }
        }
        return textParts.toString()
    }
    return content.toString()
}
