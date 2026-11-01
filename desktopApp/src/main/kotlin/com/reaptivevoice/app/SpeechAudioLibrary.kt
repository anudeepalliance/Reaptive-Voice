package com.reaptivevoice.app

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Rvoice's folder of MP3s made from text. Each MP3 has a small JSON note next to it with the text's first words, its length, and whether it was read out loud or only saved. Files older than seven days are deleted; the person keeps a copy by downloading it elsewhere.
 */
class SpeechAudioLibrary(private val audioDirectory: File) {
    private val sevenDaysInMilliseconds = 7L * 24 * 60 * 60 * 1000

    /** A new, unused WAV path in the folder for the speaking program to write into. */
    fun newRecordingWavPath(): File {
        audioDirectory.mkdirs()
        return File(audioDirectory, "speech-${System.currentTimeMillis()}.wav")
    }

    /**
     * Turns a finished WAV into an MP3 next to it, writes the note, and deletes the WAV. Slow for long audio, so call it off the main thread. Returns null when no MP3 tool is installed or the conversion fails; the WAV is then kept so nothing is lost.
     */
    fun convertWavRecordingToMp3OnDisk(wavFile: File, spokenText: String, lengthInSeconds: Double, wasSpokenOutLoud: Boolean): File? {
        if (!wavFile.isFile) {
            return null
        }
        val mp3File = File(wavFile.parentFile, wavFile.nameWithoutExtension + ".mp3")
        val encoderCommand = mp3EncoderCommand(wavFile, mp3File) ?: return null
        val encoder = ProcessBuilder(encoderCommand).redirectErrorStream(true).start()
        encoder.inputStream.readBytes()
        val didFinish = encoder.waitFor(10, TimeUnit.MINUTES)
        if (!didFinish || encoder.exitValue() != 0 || !mp3File.isFile) {
            mp3File.delete()
            return null
        }
        val measuredLength = if (lengthInSeconds > 0) lengthInSeconds else lengthOfPcm16WavInSeconds(wavFile)
        wavFile.delete()
        noteFileFor(mp3File).writeText(
            JSONObject()
                .put("title", titleFromText(spokenText))
                .put("text", spokenText)
                .put("lengthInSeconds", measuredLength)
                .put("wasSpokenOutLoud", wasSpokenOutLoud)
                .put("createdAtEpochMilliseconds", System.currentTimeMillis())
                .toString(2),
        )
        return mp3File
    }

    /** Reads every MP3 in the folder, newest first, after deleting the ones older than seven days. */
    fun listSavedAudioFromDiskAfterDeletingOldOnes(): List<SavedSpeechAudio> {
        deleteAudioOlderThanSevenDaysFromDisk()
        val mp3Files = audioDirectory.listFiles { file ->
            file.extension == "mp3"
        } ?: return emptyList()
        return mp3Files.map { mp3File ->
            val note = runCatching {
                JSONObject(noteFileFor(mp3File).readText())
            }.getOrNull() ?: JSONObject()
            val createdAtEpochMilliseconds = note.optLong("createdAtEpochMilliseconds", mp3File.lastModified())
            SavedSpeechAudio(
                filePath = mp3File.absolutePath,
                title = note.optString("title").ifBlank { mp3File.nameWithoutExtension },
                createdAtLabel = createdAtLabel(createdAtEpochMilliseconds),
                createdAtEpochMilliseconds = createdAtEpochMilliseconds,
                lengthInSeconds = note.optDouble("lengthInSeconds", 0.0),
                wasSpokenOutLoud = note.optBoolean("wasSpokenOutLoud", true),
            )
        }.sortedByDescending {
            it.createdAtEpochMilliseconds
        }
    }

    fun deleteSavedAudioFromDisk(savedAudio: SavedSpeechAudio) {
        val mp3File = File(savedAudio.filePath)
        noteFileFor(mp3File).delete()
        mp3File.delete()
    }

    /** Copies the MP3 to where the person chose in the save window. Returns a reason when the copy fails, otherwise null. */
    fun copySavedAudioToChosenFileOnDisk(savedAudio: SavedSpeechAudio, destination: File): String? {
        return try {
            File(savedAudio.filePath).copyTo(destination, overwrite = true)
            null
        } catch (exception: Exception) {
            exception.message ?: "The file could not be copied."
        }
    }

    /** A file name made from the text's first words, safe for macOS, such as "Sentence number one of a long article.mp3". */
    fun suggestedDownloadFileName(savedAudio: SavedSpeechAudio): String {
        return suggestedDownloadFileNameForTitle(savedAudio.title)
    }

    /** The same kind of file name, for text that has not been turned into audio yet. */
    fun suggestedDownloadFileNameForText(text: String): String {
        return suggestedDownloadFileNameForTitle(titleFromText(text))
    }

    private fun suggestedDownloadFileNameForTitle(title: String): String {
        val safeTitle = title.replace(Regex("[/:\\\\]"), " ").replace(Regex("\\s+"), " ").trim().take(60).trimEnd('.', ' ')
        return "${safeTitle.ifBlank { "Rvoice audio" }}.mp3"
    }

    private fun deleteAudioOlderThanSevenDaysFromDisk() {
        val oldestAllowedEpochMilliseconds = System.currentTimeMillis() - sevenDaysInMilliseconds
        audioDirectory.listFiles()?.forEach { file ->
            if (file.lastModified() < oldestAllowedEpochMilliseconds) {
                file.delete()
            }
        }
    }

    private fun noteFileFor(mp3File: File): File {
        return File(mp3File.parentFile, mp3File.nameWithoutExtension + ".json")
    }

    /** Prefers lame, then ffmpeg (both from Homebrew). Returns null when neither is installed. */
    private fun mp3EncoderCommand(wavFile: File, mp3File: File): List<String>? {
        val lame = listOf("/opt/homebrew/bin/lame", "/usr/local/bin/lame").map(::File).firstOrNull { it.canExecute() }
        if (lame != null) {
            return listOf(lame.absolutePath, "--quiet", "-V", "4", wavFile.absolutePath, mp3File.absolutePath)
        }
        val ffmpeg = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() }
        if (ffmpeg != null) {
            return listOf(ffmpeg.absolutePath, "-y", "-loglevel", "error", "-i", wavFile.absolutePath, "-codec:a", "libmp3lame", "-q:a", "4", mp3File.absolutePath)
        }
        return null
    }

    private fun lengthOfPcm16WavInSeconds(wavFile: File): Double {
        val sampleRate = 24_000.0
        return ((wavFile.length() - 44).coerceAtLeast(0) / 2) / sampleRate
    }

    private fun titleFromText(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 70) oneLine else oneLine.take(70).substringBeforeLast(' ') + "…"
    }

    private fun createdAtLabel(epochMilliseconds: Long): String {
        val createdAt = Instant.ofEpochMilli(epochMilliseconds).atZone(ZoneId.systemDefault())
        val timeText = createdAt.format(DateTimeFormatter.ofPattern("h:mm a"))
        val today = LocalDate.now()
        return when (createdAt.toLocalDate()) {
            today -> "Today $timeText"
            today.minusDays(1) -> "Yesterday $timeText"
            else -> createdAt.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " " + timeText
        }
    }
}
