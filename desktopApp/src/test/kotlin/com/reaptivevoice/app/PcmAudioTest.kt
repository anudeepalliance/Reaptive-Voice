package com.reaptivevoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmAudioTest {
    @Test
    fun writesASixteenKilohertzMonoWav() {
        val samples = ShortArray(160) { index ->
            index.toShort()
        }
        val destination = File.createTempFile("reaptive-wav", ".wav")
        writePcm16MonoWav(destination, samples, 16_000)
        val bytes = destination.readBytes()
        destination.delete()
        assertEquals(44 + 320, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(16_000, sampleRate)
    }

    @Test
    fun downsamplesFortyEightKilohertzToSixteen() {
        val samples = ShortArray(48_000) { 1_000 }
        val resampled = resampleMonoPcm16(samples, 48_000, 16_000)
        assertEquals(16_000, resampled.size)
        assertTrue(resampled.all { sample -> sample == 1_000.toShort() })
    }

    @Test
    fun treatsAQuietRoomAsNoSpeech() {
        val quietRoom = ShortArray(32_000) { index ->
            (if (index % 2 == 0) 120 else -120).toShort()
        }
        assertEquals(false, containsSpeech(quietRoom))
    }

    @Test
    fun noticesHalfASecondOfSpeech() {
        val samples = ShortArray(32_000) { index ->
            if (index in 8_000 until 16_000) {
                (if (index % 2 == 0) 3_000 else -3_000).toShort()
            } else {
                0
            }
        }
        assertTrue(containsSpeech(samples))
    }

    @Test
    fun cutsInsideThePause() {
        val samples = ShortArray(160_000) { index ->
            if (index in 96_000 until 104_000) {
                0
            } else {
                (if (index % 2 == 0) 3_000 else -3_000).toShort()
            }
        }
        val cutPoint = findQuietestCutPoint(samples, 16_000, 150_000)
        assertTrue("cut at $cutPoint", cutPoint in 96_000 until 104_000)
    }

    @Test
    fun readsBackTheSamplesItWrote() {
        val samples = ShortArray(500) { index ->
            (index * 7 - 1_000).toShort()
        }
        val wavFile = File.createTempFile("reaptive-read", ".wav")
        writePcm16MonoWav(wavFile, samples, 16_000)
        val readBack = readPcm16MonoWavSamplesFromDisk(wavFile)
        wavFile.delete()
        assertTrue(samples.contentEquals(readBack))
    }
}
