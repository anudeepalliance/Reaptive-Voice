package com.reaptivevoice.app

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns captured microphone bytes into mono 16-bit samples at 16 kHz, which is the rate LFM3-Audio-2B listens at, and writes a wav file the model can read.
 */
fun monoSamplesFromCapturedPcm(capturedBytes: ByteArray, capturedFormat: AudioFormat): ShortArray {
    if (capturedBytes.isEmpty() || capturedFormat.channels <= 0 || capturedFormat.frameSize <= 0) {
        return ShortArray(0)
    }
    val frameCount = capturedBytes.size / capturedFormat.frameSize
    val monoSamples = ShortArray(frameCount)
    var byteOffset = 0
    for (frameIndex in 0 until frameCount) {
        var channelSum = 0
        for (channelIndex in 0 until capturedFormat.channels) {
            channelSum += readOneSample(capturedBytes, byteOffset, capturedFormat)
            byteOffset += capturedFormat.sampleSizeInBits / 8
        }
        monoSamples[frameIndex] = (channelSum / capturedFormat.channels).toShort()
    }
    return monoSamples
}

private fun readOneSample(capturedBytes: ByteArray, byteOffset: Int, capturedFormat: AudioFormat): Int {
    if (capturedFormat.encoding == AudioFormat.Encoding.PCM_FLOAT && capturedFormat.sampleSizeInBits == 32) {
        val bits = readInt32(capturedBytes, byteOffset, capturedFormat.isBigEndian)
        val unitSample = Float.fromBits(bits).coerceIn(-1f, 1f)
        return (unitSample * 32767f).toInt()
    }
    if (capturedFormat.sampleSizeInBits == 16) {
        return readInt16(capturedBytes, byteOffset, capturedFormat.isBigEndian).toInt()
    }
    if (capturedFormat.sampleSizeInBits == 8) {
        val unsigned = capturedBytes[byteOffset].toInt() and 0xFF
        return (unsigned - 128) shl 8
    }
    return 0
}

private fun readInt16(capturedBytes: ByteArray, byteOffset: Int, isBigEndian: Boolean): Short {
    val first = capturedBytes[byteOffset].toInt() and 0xFF
    val second = capturedBytes[byteOffset + 1].toInt() and 0xFF
    val value = if (isBigEndian) {
        (first shl 8) or second
    } else {
        (second shl 8) or first
    }
    return value.toShort()
}

private fun readInt32(capturedBytes: ByteArray, byteOffset: Int, isBigEndian: Boolean): Int {
    val first = capturedBytes[byteOffset].toInt() and 0xFF
    val second = capturedBytes[byteOffset + 1].toInt() and 0xFF
    val third = capturedBytes[byteOffset + 2].toInt() and 0xFF
    val fourth = capturedBytes[byteOffset + 3].toInt() and 0xFF
    return if (isBigEndian) {
        (first shl 24) or (second shl 16) or (third shl 8) or fourth
    } else {
        (fourth shl 24) or (third shl 16) or (second shl 8) or first
    }
}

/**
 * Changes the sample rate with straight interpolation. Speech recorded at 44.1 or 48 kHz is brought down to 16 kHz before it is sent to the model.
 */
fun resampleMonoPcm16(samples: ShortArray, sourceSampleRate: Int, targetSampleRate: Int): ShortArray {
    if (samples.isEmpty() || sourceSampleRate == targetSampleRate) {
        return samples
    }
    val outputLength = (samples.size.toLong() * targetSampleRate / sourceSampleRate).toInt().coerceAtLeast(1)
    val resampled = ShortArray(outputLength)
    val sourcePerOutput = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
    for (outputIndex in resampled.indices) {
        val sourcePosition = outputIndex * sourcePerOutput
        val leftIndex = sourcePosition.toInt().coerceIn(0, samples.lastIndex)
        val rightIndex = (leftIndex + 1).coerceAtMost(samples.lastIndex)
        val fraction = (sourcePosition - leftIndex).toFloat()
        val mixed = samples[leftIndex] * (1f - fraction) + samples[rightIndex] * fraction
        resampled[outputIndex] = mixed.toInt().coerceIn(-32768, 32767).toShort()
    }
    return resampled
}

fun writePcm16MonoWav(destination: File, samples: ShortArray, sampleRate: Int) {
    val dataSize = samples.size * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + dataSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(sampleRate)
    header.putInt(sampleRate * 2)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)
    val sampleBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { sample ->
        sampleBytes.putShort(sample)
    }
    FileOutputStream(destination).use { stream ->
        stream.write(header.array())
        stream.write(sampleBytes.array())
    }
}

fun isNearSilence(samples: ShortArray): Boolean {
    var loudest = 0
    for (sample in samples) {
        loudest = max(loudest, abs(sample.toInt()))
        if (loudest > 400) {
            return false
        }
    }
    return true
}

fun microphoneLevelFromBuffer(buffer: ByteArray, byteCount: Int, capturedFormat: AudioFormat): Float {
    val frames = monoSamplesFromCapturedPcm(buffer.copyOf(byteCount), capturedFormat)
    if (frames.isEmpty()) {
        return 0f
    }
    var sumOfSquares = 0.0
    for (sample in frames) {
        val unit = sample / 32768.0
        sumOfSquares += unit * unit
    }
    val rootMeanSquare = kotlin.math.sqrt(sumOfSquares / frames.size)
    return (rootMeanSquare * 4.0).coerceIn(0.0, 1.0).toFloat()
}

const val speechModelSampleRate = 16_000

/**
 * Reads the samples out of a 16 kHz mono wav written by Rvoice's recorder, which always uses the plain 44-byte header.
 */
fun readPcm16MonoWavSamplesFromDisk(wavFile: File): ShortArray {
    val wavBytes = wavFile.readBytes()
    if (wavBytes.size <= 44) {
        return ShortArray(0)
    }
    val sampleBuffer = ByteBuffer.wrap(wavBytes, 44, wavBytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val samples = ShortArray(sampleBuffer.remaining())
    sampleBuffer.get(samples)
    return samples
}

/**
 * True when a recording has any sound at all that could be speech, even quiet speech. The final pass of a short take uses this instead of [containsSpeech], so a quiet speaker is still transcribed while a silent take is not sent to the model.
 *
 * Why: on silence or room noise the speech model invents text: "Thank you.", or the custom word list from its instruction ("Vocabulary: Peekopals, Meera, Ktor."). Measured rooms sit at 25 to 65 and speech peaks at 350 to 570, so the bar is 150 for a fifth of a second.
 */
fun containsAnySoundThatCouldBeSpeech(samples: ShortArray, sampleRate: Int = speechModelSampleRate): Boolean {
    return containsSpeech(samples, sampleRate, minimumSpeechMilliseconds = 200, loudnessThreshold = 150.0)
}

/**
 * True when the clip holds at least `minimumSpeechMilliseconds` (a third of a second by default) louder than `loudnessThreshold`, which by default is clearly louder than a quiet room. Live updates wait for this, because on silence the speech model tends to guess a filler word such as "Okay."
 */
fun containsSpeech(
    samples: ShortArray,
    sampleRate: Int = speechModelSampleRate,
    minimumSpeechMilliseconds: Int = 300,
    loudnessThreshold: Double = 350.0,
): Boolean {
    val windowLength = sampleRate / 50
    val loudWindowsNeeded = (minimumSpeechMilliseconds / 20).coerceAtLeast(1)
    var loudWindowCount = 0
    var windowStart = 0
    while (windowStart + windowLength <= samples.size) {
        if (rootMeanSquare(samples, windowStart, windowStart + windowLength) > loudnessThreshold) {
            loudWindowCount++
            if (loudWindowCount >= loudWindowsNeeded) {
                return true
            }
        }
        windowStart += windowLength
    }
    return false
}

/**
 * Finds the quietest fifth of a second between the two sample positions and returns its middle. Long takes are cut there, in a pause, so no word is split in half. If the range is too short, the end of the range is returned.
 */
fun findQuietestCutPoint(samples: ShortArray, searchStart: Int, searchEnd: Int, sampleRate: Int = speechModelSampleRate): Int {
    val windowLength = sampleRate / 5
    val stepLength = sampleRate / 20
    val safeSearchStart = searchStart.coerceIn(0, samples.size)
    val safeSearchEnd = searchEnd.coerceIn(safeSearchStart, samples.size)
    var quietestLoudness = Double.MAX_VALUE
    var quietestCutPoint = safeSearchEnd
    var windowStart = safeSearchStart
    while (windowStart + windowLength <= safeSearchEnd) {
        val loudness = rootMeanSquare(samples, windowStart, windowStart + windowLength)
        if (loudness < quietestLoudness) {
            quietestLoudness = loudness
            quietestCutPoint = windowStart + windowLength / 2
        }
        windowStart += stepLength
    }
    return quietestCutPoint
}

private fun rootMeanSquare(samples: ShortArray, fromIndex: Int, toIndex: Int): Double {
    if (toIndex <= fromIndex) {
        return 0.0
    }
    var sumOfSquares = 0.0
    for (sampleIndex in fromIndex until toIndex) {
        val sample = samples[sampleIndex].toDouble()
        sumOfSquares += sample * sample
    }
    return kotlin.math.sqrt(sumOfSquares / (toIndex - fromIndex))
}
