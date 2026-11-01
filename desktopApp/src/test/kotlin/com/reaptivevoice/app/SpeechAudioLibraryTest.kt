package com.reaptivevoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpeechAudioLibraryTest {
    private fun libraryInNewFolder(): Pair<SpeechAudioLibrary, File> {
        val folder = Files.createTempDirectory("rvoice-audio").toFile()
        return SpeechAudioLibrary(folder) to folder
    }

    private fun writeOneSecondTone(wavFile: File) {
        val samples = ShortArray(24_000) { index ->
            (8_000 * kotlin.math.sin(index * 2 * Math.PI * 440 / 24_000)).toInt().toShort()
        }
        writePcm16MonoWav(wavFile, samples, 24_000)
    }

    @Test
    fun turnsARecordingIntoAnMp3WithItsTitleAndLength() {
        val lameIsInstalled = File("/opt/homebrew/bin/lame").canExecute() || File("/usr/local/bin/lame").canExecute()
        assumeTrue("lame is not installed", lameIsInstalled)
        val (library, folder) = libraryInNewFolder()
        val wavFile = library.newRecordingWavPath()
        writeOneSecondTone(wavFile)
        val mp3File = library.convertWavRecordingToMp3OnDisk(wavFile, "Peekopals launch notes for the team", 1.0, wasSpokenOutLoud = false)
        assertTrue(mp3File != null && mp3File.isFile && mp3File.length() > 1_000)
        assertFalse("the WAV is removed once the MP3 exists", wavFile.exists())
        val savedAudio = library.listSavedAudioFromDiskAfterDeletingOldOnes().single()
        assertEquals("Peekopals launch notes for the team", savedAudio.title)
        assertEquals(1.0, savedAudio.lengthInSeconds, 0.01)
        assertFalse(savedAudio.wasSpokenOutLoud)
        folder.deleteRecursively()
    }

    @Test
    fun deletesAudioOlderThanSevenDays() {
        val (library, folder) = libraryInNewFolder()
        val oldMp3 = File(folder, "speech-1.mp3").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        }
        val newMp3 = File(folder, "speech-2.mp3").apply {
            writeText("new")
        }
        val keptAudio = library.listSavedAudioFromDiskAfterDeletingOldOnes()
        assertFalse(oldMp3.exists())
        assertTrue(newMp3.exists())
        assertEquals(1, keptAudio.size)
        folder.deleteRecursively()
    }

    @Test
    fun suggestsASafeDownloadName() {
        val (library, folder) = libraryInNewFolder()
        val savedAudio = SavedSpeechAudio("/tmp/x.mp3", "Notes: plan/launch for Q3.", "Today 2:50 PM", 0, 10.0, true)
        assertEquals("Notes plan launch for Q3.mp3", library.suggestedDownloadFileName(savedAudio))
        folder.deleteRecursively()
    }
}
