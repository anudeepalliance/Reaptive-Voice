package com.reaptivevoice.app

/**
 * One MP3 Rvoice made from text, either while reading it out loud or with "Save as MP3". Files are deleted seven days after they were made unless the person downloads a copy.
 *
 * @property filePath Where the MP3 lives inside Rvoice's own folder.
 * @property title The first words of the text, used as the row title and the suggested download name.
 * @property createdAtLabel When it was made, ready to show ("Today 2:50 PM").
 * @property lengthInSeconds How long the audio plays at normal speed.
 * @property wasSpokenOutLoud True when it was recorded while being read out loud; false when it came from "Save as MP3".
 */
data class SavedSpeechAudio(
    val filePath: String,
    val title: String,
    val createdAtLabel: String,
    val createdAtEpochMilliseconds: Long,
    val lengthInSeconds: Double,
    val wasSpokenOutLoud: Boolean,
)

/**
 * How far along "Save as MP3" is: the text is split into parts and each finished part counts once.
 */
data class SpeechFileProgress(
    val finishedPartCount: Int,
    val totalPartCount: Int,
)

/** The playback speeds offered, slowest first. */
val speakingSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Shows a speed the way players do: "1x", "0.75x", "1.5x". */
fun speakingSpeedLabel(speed: Float): String {
    val roundedSpeed = (speed * 100).toInt() / 100f
    val speedText = if (roundedSpeed == roundedSpeed.toInt().toFloat()) {
        roundedSpeed.toInt().toString()
    } else {
        roundedSpeed.toString().trimEnd('0')
    }
    return "${speedText}x"
}

/** Shows a length as minutes and seconds, such as "2:05". */
fun formatLengthAsMinutesAndSeconds(lengthInSeconds: Double): String {
    val totalSeconds = lengthInSeconds.toInt().coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/**
 * What the built-in player shows for the MP3 it has open.
 *
 * @property filePath The MP3 being played.
 * @property positionInSeconds How far into the file playback is.
 * @property isPlaying False while paused or after reaching the end.
 */
data class SavedAudioPlayerState(
    val filePath: String,
    val title: String,
    val positionInSeconds: Double,
    val lengthInSeconds: Double,
    val isPlaying: Boolean,
)
