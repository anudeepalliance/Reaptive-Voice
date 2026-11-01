/**
 * Rvoice's speaking program. It loads the LFM3-Audio-2B model once, then waits for text on its input and reads it
 * out loud through the Mac's speakers. Audio plays while it is still being generated, so speech starts within a
 * fraction of a second instead of after the whole text is done.
 *
 * Commands, one per line on standard input:
 *   speak <wav path>\t<text>
 *                   reads the text out loud (anything already playing is stopped first), then writes what was
 *                   spoken to the WAV path; leave the path empty to not save
 *   save <wav path>\t<text>
 *                   only turns the text into a WAV file, without playing it; for long texts to hear later
 *   cancel-save     stops making the file
 *   speed <0.5 to 2.0> playback speed; changes the voice's pace, not its pitch, and applies at once
 *   style <language code>\t<language name>\t<female|male>
 *                   sets the accent and voice for the next "speak", e.g. "style en-gb\tBritish English\tmale"
 *   stop            stops speaking right away
 *   pause / resume  pauses or continues playback (generation keeps going in the background)
 *   rewind <seconds> jumps back and replays from there
 *   quit            exits
 *
 * Messages, one JSON object per line on standard output:
 *   {"type":"ready"}                         the model is loaded
 *   {"type":"started"}                       the first sound is playing
 *   {"type":"level","level":0.42}            loudness of what is playing now, about ten times a second
 *   {"type":"paused"} / {"type":"resumed"}   playback was paused or continued
 *   {"type":"finished","stopped":false}      done speaking (stopped is true when "stop" cut it short)
 *   {"type":"recorded","path":"..."}         what was just spoken was written to this WAV file
 *   {"type":"save-progress","done":3,"total":12}  progress while only making a file
 *   {"type":"saved","path":"...","seconds":95.2} / {"type":"save-failed","message":"..."} / {"type":"save-cancelled"}
 *   {"type":"busy","message":"..."}          a request was refused because a file is still being made
 *   {"type":"error","message":"..."}         something went wrong
 *
 * Usage: rvoice-speaker <model.gguf> <mmproj.gguf>
 */

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <AudioToolbox/AudioToolbox.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

std::mutex standardOutputLock;

/** Writes one JSON message line to standard output. */
void sendMessageToApp(const std::string & jsonLine) {
    std::lock_guard<std::mutex> guard(standardOutputLock);
    std::fwrite(jsonLine.data(), 1, jsonLine.size(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

std::string escapeForJson(const std::string & text) {
    std::string escaped;
    for (char character : text) {
        switch (character) {
            case '"': escaped += "\\\""; break;
            case '\\': escaped += "\\\\"; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default:
                if (static_cast<unsigned char>(character) < 0x20) {
                    escaped += ' ';
                } else {
                    escaped += character;
                }
        }
    }
    return escaped;
}

void sendErrorToApp(const std::string & message) {
    sendMessageToApp("{\"type\":\"error\",\"message\":\"" + escapeForJson(message) + "\"}");
}

/**
 * Splits text into pieces of whole sentences, each at most about 260 characters. The model speaks short pieces more
 * steadily, and the first piece is ready sooner. A sentence longer than the limit is split at the last space.
 */
std::vector<std::string> splitTextIntoSpeakablePieces(const std::string & text) {
    const size_t longestPieceLength = 260;
    std::vector<std::string> sentences;
    std::string currentSentence;
    for (size_t characterIndex = 0; characterIndex < text.size(); characterIndex++) {
        const char character = text[characterIndex];
        currentSentence += character == '\n' || character == '\r' || character == '\t' ? ' ' : character;
        const bool endsSentence = character == '.' || character == '!' || character == '?' || character == '\n';
        const bool nextIsSpaceOrEnd = characterIndex + 1 >= text.size() || std::isspace(static_cast<unsigned char>(text[characterIndex + 1]));
        if (endsSentence && nextIsSpaceOrEnd) {
            sentences.push_back(currentSentence);
            currentSentence.clear();
        }
    }
    sentences.push_back(currentSentence);

    std::vector<std::string> pieces;
    std::string currentPiece;
    auto finishPiece = [&]() {
        size_t firstVisible = currentPiece.find_first_not_of(' ');
        size_t lastVisible = currentPiece.find_last_not_of(' ');
        if (firstVisible != std::string::npos) {
            pieces.push_back(currentPiece.substr(firstVisible, lastVisible - firstVisible + 1));
        }
        currentPiece.clear();
    };
    for (std::string sentence : sentences) {
        while (sentence.size() > longestPieceLength) {
            size_t cutAt = sentence.rfind(' ', longestPieceLength);
            if (cutAt == std::string::npos || cutAt == 0) {
                cutAt = longestPieceLength;
            }
            finishPiece();
            currentPiece = sentence.substr(0, cutAt);
            finishPiece();
            sentence = sentence.substr(cutAt);
        }
        if (currentPiece.size() + sentence.size() > longestPieceLength) {
            finishPiece();
        }
        currentPiece += sentence;
    }
    finishPiece();
    return pieces;
}

/**
 * Plays mono 32-bit float audio through the default output as it arrives, and keeps everything played so far so the
 * listener can pause, resume, and jump back. Playback starts once a little audio is waiting, so short gaps in
 * generation do not cause stutters. All methods may be called from different threads.
 */
class StreamingSpeakerOutput {
public:
    explicit StreamingSpeakerOutput(double sampleRate) : sampleRate(sampleRate) {}

    ~StreamingSpeakerOutput() {
        std::lock_guard<std::mutex> guard(outputLock);
        disposeAudioQueue();
    }

    /** Keeps the new samples and sends them to the speakers. Starts playback once enough audio is waiting. */
    bool queueSamplesForPlayback(const float * samples, size_t sampleCount) {
        if (sampleCount == 0) {
            return true;
        }
        std::lock_guard<std::mutex> guard(outputLock);
        const size_t samplesPerLoudnessStep = static_cast<size_t>(sampleRate / 10);
        for (size_t stepStart = 0; stepStart < sampleCount; stepStart += samplesPerLoudnessStep) {
            const size_t stepEnd = std::min(sampleCount, stepStart + samplesPerLoudnessStep);
            double sumOfSquares = 0;
            for (size_t sampleIndex = stepStart; sampleIndex < stepEnd; sampleIndex++) {
                sumOfSquares += samples[sampleIndex] * samples[sampleIndex];
            }
            const double rootMeanSquare = std::sqrt(sumOfSquares / std::max<size_t>(1, stepEnd - stepStart));
            loudnessSteps.push_back({allSamples.size() + stepStart, std::min(1.0, rootMeanSquare * 5.0)});
        }
        allSamples.insert(allSamples.end(), samples, samples + sampleCount);
        if (!audioQueue && !createAudioQueueStartingAt(0)) {
            return false;
        }
        if (!sendWaitingSamplesToAudioQueue()) {
            return false;
        }
        const size_t samplesBeforeStarting = static_cast<size_t>(sampleRate * 0.24);
        if (!hasStarted && allSamples.size() >= samplesBeforeStarting) {
            startOrResumeAudioQueue();
        }
        return true;
    }

    /** Starts playback even if less than the usual amount of audio is waiting, for very short texts. */
    void startPlaybackIfNotStarted() {
        std::lock_guard<std::mutex> guard(outputLock);
        if (audioQueue && !hasStarted && !allSamples.empty()) {
            startOrResumeAudioQueue();
        }
    }

    bool hasStartedPlaying() {
        std::lock_guard<std::mutex> guard(outputLock);
        return hasStarted;
    }

    bool isPausedByListener() {
        std::lock_guard<std::mutex> guard(outputLock);
        return isPaused;
    }

    /**
     * True once everything generated so far has reached the speakers, allowing a quarter second for the audio the
     * speed changer holds back at the very end.
     */
    bool hasPlayedEverythingGenerated() {
        std::lock_guard<std::mutex> guard(outputLock);
        const size_t allowedShortfall = static_cast<size_t>(sampleRate / 4);
        return !isPaused && playedSamplePosition() + allowedShortfall >= allSamples.size();
    }

    /** Where playback is now and how much has been generated, both counted in samples from the start. */
    std::pair<size_t, size_t> playbackPositionAndGeneratedLength() {
        std::lock_guard<std::mutex> guard(outputLock);
        return {playedSamplePosition(), allSamples.size()};
    }

    /** Loudness (0 to 1) of the audio playing right now; 0 while paused. */
    double loudnessOfWhatIsPlayingNow() {
        std::lock_guard<std::mutex> guard(outputLock);
        if (isPaused || loudnessSteps.empty()) {
            return 0.0;
        }
        const size_t playedSamples = playedSamplePosition();
        size_t low = 0;
        size_t high = loudnessSteps.size();
        while (high - low > 1) {
            const size_t middle = (low + high) / 2;
            if (loudnessSteps[middle].firstSampleIndex <= playedSamples) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return loudnessSteps[low].loudness;
    }

    void pause() {
        std::lock_guard<std::mutex> guard(outputLock);
        if (audioQueue && hasStarted && !isPaused) {
            AudioQueuePause(audioQueue);
            isPaused = true;
        }
    }

    void resume() {
        std::lock_guard<std::mutex> guard(outputLock);
        if (isPaused) {
            isPaused = false;
            startOrResumeAudioQueue();
        }
    }

    /**
     * Jumps back by the given number of seconds (to the start at most) and plays from there. If playback was paused, it
     * stays paused at the new position.
     */
    void jumpBack(double seconds) {
        std::lock_guard<std::mutex> guard(outputLock);
        if (!audioQueue || !hasStarted) {
            return;
        }
        const size_t currentPosition = playedSamplePosition();
        const size_t jumpLength = static_cast<size_t>(seconds * sampleRate);
        const size_t newPosition = currentPosition > jumpLength ? currentPosition - jumpLength : 0;
        disposeAudioQueue();
        if (!createAudioQueueStartingAt(newPosition) || !sendWaitingSamplesToAudioQueue()) {
            return;
        }
        if (!isPaused) {
            startOrResumeAudioQueue();
        }
    }

    /** Changes how fast the voice plays (0.5 to 2), keeping its pitch. Applies at once, also mid-sentence. */
    void setPlaybackSpeed(double speed) {
        std::lock_guard<std::mutex> guard(outputLock);
        playbackSpeed = speed;
        if (audioQueue) {
            AudioQueueSetParameter(audioQueue, kAudioQueueParam_PlayRate, static_cast<AudioQueueParameterValue>(playbackSpeed));
        }
    }

    std::vector<float> copyOfAllGeneratedSamples() {
        std::lock_guard<std::mutex> guard(outputLock);
        return allSamples;
    }

    /** Silences the speakers right away and throws away anything still queued. */
    void stopImmediately() {
        std::lock_guard<std::mutex> guard(outputLock);
        disposeAudioQueue();
        hasStarted = false;
        isPaused = false;
    }

private:
    struct LoudnessStep {
        size_t firstSampleIndex;
        double loudness;
    };

    static void freePlayedBuffer(void *, AudioQueueRef queue, AudioQueueBufferRef buffer) {
        AudioQueueFreeBuffer(queue, buffer);
    }

    /** A new audio queue plays from the given position in everything generated so far. */
    bool createAudioQueueStartingAt(size_t startPosition) {
        AudioStreamBasicDescription format{};
        format.mSampleRate = sampleRate;
        format.mFormatID = kAudioFormatLinearPCM;
        format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
        format.mBytesPerPacket = sizeof(float);
        format.mFramesPerPacket = 1;
        format.mBytesPerFrame = sizeof(float);
        format.mChannelsPerFrame = 1;
        format.mBitsPerChannel = 32;
        if (AudioQueueNewOutput(&format, freePlayedBuffer, nullptr, nullptr, nullptr, 0, &audioQueue) != noErr) {
            audioQueue = nullptr;
            return false;
        }
        const UInt32 shouldChangeSpeedWithoutChangingPitch = 1;
        AudioQueueSetProperty(audioQueue, kAudioQueueProperty_EnableTimePitch, &shouldChangeSpeedWithoutChangingPitch, sizeof(shouldChangeSpeedWithoutChangingPitch));
        const UInt32 speechFriendlyAlgorithm = kAudioQueueTimePitchAlgorithm_Spectral;
        AudioQueueSetProperty(audioQueue, kAudioQueueProperty_TimePitchAlgorithm, &speechFriendlyAlgorithm, sizeof(speechFriendlyAlgorithm));
        AudioQueueSetParameter(audioQueue, kAudioQueueParam_PlayRate, static_cast<AudioQueueParameterValue>(playbackSpeed));
        queueStartPosition = startPosition;
        sentToQueueUpTo = startPosition;
        lastKnownPlayedPosition = startPosition;
        return true;
    }

    /** Sends every sample not yet in the audio queue, in buffers of at most a quarter second. */
    bool sendWaitingSamplesToAudioQueue() {
        const size_t largestBufferInSamples = static_cast<size_t>(sampleRate / 4);
        while (sentToQueueUpTo < allSamples.size()) {
            const size_t bufferSampleCount = std::min(largestBufferInSamples, allSamples.size() - sentToQueueUpTo);
            const UInt32 byteCount = static_cast<UInt32>(bufferSampleCount * sizeof(float));
            AudioQueueBufferRef buffer = nullptr;
            if (AudioQueueAllocateBuffer(audioQueue, byteCount, &buffer) != noErr) {
                return false;
            }
            std::memcpy(buffer->mAudioData, allSamples.data() + sentToQueueUpTo, byteCount);
            buffer->mAudioDataByteSize = byteCount;
            if (AudioQueueEnqueueBuffer(audioQueue, buffer, 0, nullptr) != noErr) {
                AudioQueueFreeBuffer(audioQueue, buffer);
                return false;
            }
            sentToQueueUpTo += bufferSampleCount;
        }
        return true;
    }

    void startOrResumeAudioQueue() {
        if (audioQueue && AudioQueueStart(audioQueue, nullptr) == noErr) {
            hasStarted = true;
        }
    }

    void disposeAudioQueue() {
        if (audioQueue) {
            lastKnownPlayedPosition = playedSamplePosition();
            AudioQueueStop(audioQueue, true);
            AudioQueueDispose(audioQueue, true);
            audioQueue = nullptr;
        }
    }

    /** Where playback is, counted from the start of everything generated. Must be called with outputLock held. */
    size_t playedSamplePosition() {
        if (!audioQueue) {
            return lastKnownPlayedPosition;
        }
        AudioTimeStamp currentTime{};
        if (AudioQueueGetCurrentTime(audioQueue, nullptr, &currentTime, nullptr) == noErr &&
            (currentTime.mFlags & kAudioTimeStampSampleTimeValid) && currentTime.mSampleTime >= 0) {
            lastKnownPlayedPosition = std::min(allSamples.size(), queueStartPosition + static_cast<size_t>(currentTime.mSampleTime));
        }
        return lastKnownPlayedPosition;
    }

    double sampleRate;
    double playbackSpeed = 1.0;
    std::mutex outputLock;
    AudioQueueRef audioQueue = nullptr;
    bool hasStarted = false;
    bool isPaused = false;
    std::vector<float> allSamples;
    std::vector<LoudnessStep> loudnessSteps;
    size_t queueStartPosition = 0;
    size_t sentToQueueUpTo = 0;
    size_t lastKnownPlayedPosition = 0;
};

/** The accent and voice the model is asked to use. The language name goes into the model's prompt word for word. */
struct SpeakingStyle {
    std::string languageCode = "en";
    std::string languageName = "American English";
    std::string voice = "female";
};

/** The loaded model plus everything needed to turn text into speech with it. */
struct LoadedSpeechModel {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * audioContext = nullptr;
    llama_sampler * tokenPicker = nullptr;
    std::string startOfTextToken;
};

bool loadSpeechModelOnce(const char * modelPath, const char * projectorPath, LoadedSpeechModel & loaded) {
    llama_backend_init();
    llama_model_params modelParams = llama_model_default_params();
    loaded.model = llama_model_load_from_file(modelPath, modelParams);
    if (!loaded.model) {
        sendErrorToApp("The speech model could not be loaded.");
        return false;
    }
    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = 4096;
    contextParams.n_batch = 512;
    contextParams.n_ubatch = 512;
    contextParams.embeddings = true;
    loaded.context = llama_init_from_model(loaded.model, contextParams);
    if (!loaded.context) {
        sendErrorToApp("The speech model could not start.");
        return false;
    }
    mtmd_context_params audioParams = mtmd_context_params_default();
    audioParams.use_gpu = true;
    loaded.audioContext = mtmd_init_from_file(projectorPath, loaded.model, audioParams);
    if (!loaded.audioContext) {
        sendErrorToApp("The speech model's audio part could not be loaded.");
        return false;
    }
    if (mtmd_gen_audio_get_info(loaded.audioContext).type == MTMD_GEN_AUDIO_TYPE_NONE) {
        sendErrorToApp("This model cannot speak.");
        return false;
    }
    loaded.tokenPicker = llama_sampler_init_greedy();
    const llama_vocab * vocabulary = llama_model_get_vocab(loaded.model);
    const llama_token startToken = llama_vocab_bos(vocabulary);
    if (startToken != LLAMA_TOKEN_NULL) {
        const char * startText = llama_vocab_get_text(vocabulary, startToken);
        loaded.startOfTextToken = startText ? startText : "";
    }
    return true;
}

/** Builds the same prompt the model's own speaking template builds, with the chosen accent and voice. */
std::string buildSpeakingPromptForText(const LoadedSpeechModel & loaded, const SpeakingStyle & style, const std::string & text) {
    return loaded.startOfTextToken +
        "<|im_start|>system\nPerform TTS in " + style.languageName + " using a " + style.voice + " voice.<|im_end|>\n"
        "<|im_start|>user\n" + text + "<|im_end|>\n"
        "<|im_start|>assistant\n<|audio_start|>";
}

/**
 * Turns one piece of text into speech, handing each new bit of audio to `receiveSamples` as soon as it is generated.
 * Returns false on a model error or when `receiveSamples` returns false. Returns early, without an error, when
 * shouldStop becomes true.
 */
bool generateSpeechForOnePiece(LoadedSpeechModel & loaded, const SpeakingStyle & style, const std::string & piece,
                               const std::function<bool(const float *, size_t)> & receiveSamples,
                               const std::atomic<bool> & shouldStop) {
    llama_memory_clear(llama_get_memory(loaded.context), true);
    llama_sampler_reset(loaded.tokenPicker);
    mtmd_helper_gen_audio * generator = mtmd_helper_gen_audio_init(loaded.context, loaded.audioContext);
    if (!generator) {
        return false;
    }
    const std::string prompt = buildSpeakingPromptForText(loaded, style, piece);
    mtmd_helper_gen_audio_inp input = mtmd_helper_gen_audio_inp_default();
    input.stream = true;
    input.seq_id = 0;
    input.prompt = prompt.c_str();
    input.prompt_len = prompt.size();
    input.lang = style.languageCode.c_str();
    input.voice = style.voice.c_str();
    input.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;

    auto passNewAudioOn = [&]() -> bool {
        int32_t sampleRate = 0;
        const char * audioBytes = nullptr;
        size_t audioByteCount = 0;
        if (mtmd_helper_gen_audio_get_output(generator, &sampleRate, &audioBytes, &audioByteCount, nullptr) != 0) {
            return false;
        }
        return receiveSamples(reinterpret_cast<const float *>(audioBytes), audioByteCount / sizeof(float));
    };

    bool succeeded = mtmd_helper_gen_audio_set_input(generator, &input) == 0;
    while (succeeded && !shouldStop) {
        const int32_t remainingPromptTokens = mtmd_helper_gen_audio_step_prompt(generator, 512);
        if (remainingPromptTokens < 0) {
            succeeded = false;
        }
        if (remainingPromptTokens <= 0) {
            break;
        }
    }
    const int longestPieceInFrames = 1200;
    const float * hiddenState = succeeded ? llama_get_embeddings_ith(loaded.context, -1) : nullptr;
    for (int frameIndex = 0; succeeded && !shouldStop && frameIndex < longestPieceInFrames; frameIndex++) {
        const llama_token pickedToken = llama_sampler_sample(loaded.tokenPicker, loaded.context, -1);
        const float * nextHiddenState = nullptr;
        bool reachedEndOfSpeech = false;
        if (mtmd_helper_gen_audio_step_gen(generator, pickedToken, hiddenState, &nextHiddenState, &reachedEndOfSpeech) != 0) {
            succeeded = false;
            break;
        }
        if (!passNewAudioOn()) {
            succeeded = false;
            break;
        }
        if (reachedEndOfSpeech || !nextHiddenState) {
            break;
        }
        hiddenState = nextHiddenState;
    }
    if (succeeded && !shouldStop) {
        succeeded = mtmd_helper_gen_audio_flush(generator) == 0 && passNewAudioOn();
    }
    mtmd_helper_gen_audio_free(generator);
    return succeeded;
}

/** Writes mono float samples as a 16-bit WAV file. Returns false if the file cannot be written. */
bool writeSamplesToWavFile(const std::vector<float> & samples, int sampleRate, const std::string & wavPath) {
    FILE * wavFile = std::fopen(wavPath.c_str(), "wb");
    if (!wavFile) {
        return false;
    }
    auto writeLittleEndian = [&](uint32_t value, int byteCount) {
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++) {
            std::fputc(static_cast<int>((value >> (8 * byteIndex)) & 0xFF), wavFile);
        }
    };
    const uint32_t dataByteCount = static_cast<uint32_t>(samples.size() * 2);
    std::fwrite("RIFF", 1, 4, wavFile);
    writeLittleEndian(36 + dataByteCount, 4);
    std::fwrite("WAVEfmt ", 1, 8, wavFile);
    writeLittleEndian(16, 4);
    writeLittleEndian(1, 2);
    writeLittleEndian(1, 2);
    writeLittleEndian(static_cast<uint32_t>(sampleRate), 4);
    writeLittleEndian(static_cast<uint32_t>(sampleRate * 2), 4);
    writeLittleEndian(2, 2);
    writeLittleEndian(16, 2);
    std::fwrite("data", 1, 4, wavFile);
    writeLittleEndian(dataByteCount, 4);
    for (float sample : samples) {
        const float clampedSample = std::max(-1.0f, std::min(1.0f, sample));
        writeLittleEndian(static_cast<uint32_t>(static_cast<uint16_t>(static_cast<int16_t>(clampedSample * 32767.0f))), 2);
    }
    const bool didWriteEverything = std::ferror(wavFile) == 0;
    std::fclose(wavFile);
    return didWriteEverything;
}

/**
 * One piece of work for the speaking thread: either read text out loud now ("speak"), or only turn it into an audio
 * file for later ("save"). A non-empty wavPath means the audio is also written there.
 */
struct SpeechJob {
    bool shouldPlayOutLoud = true;
    std::string wavPath;
    std::string text;
};

/** Holds the newest job until the speaking thread picks it up, plus the voice and speed to use. */
struct SpeechJobMailbox {
    std::mutex lock;
    std::condition_variable newJobArrived;
    std::deque<SpeechJob> waitingJobs;
    SpeakingStyle style;
    double playbackSpeed = 1.0;
    bool isSavingAFile = false;
    bool shouldQuit = false;
};

/** Splits "<wav path>\t<text>" into its two parts. The path may be empty, meaning "do not save". */
SpeechJob speechJobFromCommandArguments(const std::string & arguments, bool shouldPlayOutLoud) {
    SpeechJob job;
    job.shouldPlayOutLoud = shouldPlayOutLoud;
    const size_t tabPosition = arguments.find('\t');
    if (tabPosition == std::string::npos) {
        job.text = arguments;
    } else {
        job.wavPath = arguments.substr(0, tabPosition);
        job.text = arguments.substr(tabPosition + 1);
    }
    return job;
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <model.gguf> <mmproj.gguf>\n", argv[0]);
        return 2;
    }
    llama_log_set([](ggml_log_level, const char * text, void *) {
        std::fputs(text, stderr);
    }, nullptr);
    mtmd_helper_log_set([](ggml_log_level, const char * text, void *) {
        std::fputs(text, stderr);
    }, nullptr);

    LoadedSpeechModel loaded;
    if (!loadSpeechModelOnce(argv[1], argv[2], loaded)) {
        return 1;
    }
    const int modelSampleRate = mtmd_gen_audio_get_info(loaded.audioContext).sample_rate;
    sendMessageToApp("{\"type\":\"ready\"}");

    SpeechJobMailbox mailbox;
    std::atomic<bool> shouldStopCurrentJob{false};
    std::mutex currentOutputLock;
    StreamingSpeakerOutput * currentSpeakerOutput = nullptr;

    /** Reads text out loud, playing audio as it is generated, and writes the audio to job.wavPath afterwards. */
    auto speakJobOutLoud = [&](const SpeechJob & job, const SpeakingStyle & style, double playbackSpeed) {
        StreamingSpeakerOutput speakerOutput(modelSampleRate);
        speakerOutput.setPlaybackSpeed(playbackSpeed);
        {
            std::lock_guard<std::mutex> guard(currentOutputLock);
            currentSpeakerOutput = &speakerOutput;
        }
        std::atomic<bool> isDoneGenerating{false};
        std::atomic<bool> didAnnounceStart{false};
        std::thread playbackWatcher([&]() {
            const bool shouldWriteDebugLines = std::getenv("RVOICE_SPEAKER_DEBUG") != nullptr;
            size_t lastSeenPlaybackPosition = 0;
            auto lastTimePlaybackMoved = std::chrono::steady_clock::now();
            while (!shouldStopCurrentJob) {
                const auto [playbackPosition, generatedLength] = speakerOutput.playbackPositionAndGeneratedLength();
                if (playbackPosition != lastSeenPlaybackPosition) {
                    lastSeenPlaybackPosition = playbackPosition;
                    lastTimePlaybackMoved = std::chrono::steady_clock::now();
                }
                if (shouldWriteDebugLines) {
                    std::fprintf(stderr, "playback %zu of %zu samples, done generating: %d\n", playbackPosition, generatedLength, isDoneGenerating.load() ? 1 : 0);
                }
                /* Generation is over and playback has not moved for most of a second while not paused: the end was reached. */
                const bool hasPlaybackStalledAtTheEnd = isDoneGenerating && !speakerOutput.isPausedByListener() &&
                    std::chrono::steady_clock::now() - lastTimePlaybackMoved > std::chrono::milliseconds(800);
                if (speakerOutput.hasStartedPlaying() && hasPlaybackStalledAtTheEnd) {
                    return;
                }
                if (speakerOutput.hasStartedPlaying()) {
                    if (!didAnnounceStart.exchange(true)) {
                        sendMessageToApp("{\"type\":\"started\"}");
                    }
                    char levelLine[64];
                    std::snprintf(levelLine, sizeof(levelLine), "{\"type\":\"level\",\"level\":%.3f}", speakerOutput.loudnessOfWhatIsPlayingNow());
                    sendMessageToApp(levelLine);
                    if (isDoneGenerating && speakerOutput.hasPlayedEverythingGenerated()) {
                        return;
                    }
                } else if (isDoneGenerating) {
                    return;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        });
        bool hadError = false;
        for (const std::string & piece : splitTextIntoSpeakablePieces(job.text)) {
            if (shouldStopCurrentJob) {
                break;
            }
            const bool succeeded = generateSpeechForOnePiece(loaded, style, piece, [&](const float * samples, size_t sampleCount) {
                return speakerOutput.queueSamplesForPlayback(samples, sampleCount);
            }, shouldStopCurrentJob);
            if (!succeeded) {
                hadError = true;
                break;
            }
        }
        speakerOutput.startPlaybackIfNotStarted();
        isDoneGenerating = true;
        playbackWatcher.join();
        const bool wasStopped = shouldStopCurrentJob.load();
        {
            std::lock_guard<std::mutex> guard(currentOutputLock);
            currentSpeakerOutput = nullptr;
        }
        speakerOutput.stopImmediately();
        if (hadError) {
            sendErrorToApp("The speech model failed while speaking.");
        }
        const std::vector<float> spokenSamples = speakerOutput.copyOfAllGeneratedSamples();
        if (!job.wavPath.empty() && !spokenSamples.empty() && writeSamplesToWavFile(spokenSamples, modelSampleRate, job.wavPath)) {
            sendMessageToApp("{\"type\":\"recorded\",\"path\":\"" + escapeForJson(job.wavPath) + "\"}");
        }
        sendMessageToApp(std::string("{\"type\":\"finished\",\"stopped\":") + (wasStopped ? "true" : "false") + "}");
    };

    /** Turns text into an audio file without playing it, reporting progress after each piece. */
    auto saveJobToFile = [&](const SpeechJob & job, const SpeakingStyle & style) {
        const std::vector<std::string> pieces = splitTextIntoSpeakablePieces(job.text);
        std::vector<float> generatedSamples;
        bool hadError = false;
        for (size_t pieceIndex = 0; pieceIndex < pieces.size() && !shouldStopCurrentJob; pieceIndex++) {
            const bool succeeded = generateSpeechForOnePiece(loaded, style, pieces[pieceIndex], [&](const float * samples, size_t sampleCount) {
                generatedSamples.insert(generatedSamples.end(), samples, samples + sampleCount);
                return true;
            }, shouldStopCurrentJob);
            if (!succeeded) {
                hadError = true;
                break;
            }
            sendMessageToApp("{\"type\":\"save-progress\",\"done\":" + std::to_string(pieceIndex + 1) + ",\"total\":" + std::to_string(pieces.size()) + "}");
        }
        if (shouldStopCurrentJob) {
            sendMessageToApp("{\"type\":\"save-cancelled\"}");
        } else if (hadError || generatedSamples.empty()) {
            sendMessageToApp("{\"type\":\"save-failed\",\"message\":\"The speech model failed while making the file.\"}");
        } else if (!writeSamplesToWavFile(generatedSamples, modelSampleRate, job.wavPath)) {
            sendMessageToApp("{\"type\":\"save-failed\",\"message\":\"The audio file could not be written.\"}");
        } else {
            char secondsText[32];
            std::snprintf(secondsText, sizeof(secondsText), "%.1f", static_cast<double>(generatedSamples.size()) / modelSampleRate);
            sendMessageToApp("{\"type\":\"saved\",\"path\":\"" + escapeForJson(job.wavPath) + "\",\"seconds\":" + secondsText + "}");
        }
    };

    std::thread speakingThread([&]() {
        for (;;) {
            SpeechJob job;
            SpeakingStyle styleForThisJob;
            double playbackSpeedForThisJob = 1.0;
            {
                std::unique_lock<std::mutex> guard(mailbox.lock);
                mailbox.newJobArrived.wait(guard, [&]() {
                    return mailbox.shouldQuit || !mailbox.waitingJobs.empty();
                });
                if (mailbox.shouldQuit) {
                    return;
                }
                job = mailbox.waitingJobs.back();
                mailbox.waitingJobs.clear();
                styleForThisJob = mailbox.style;
                playbackSpeedForThisJob = mailbox.playbackSpeed;
                mailbox.isSavingAFile = !job.shouldPlayOutLoud;
                shouldStopCurrentJob = false;
            }
            if (job.shouldPlayOutLoud) {
                speakJobOutLoud(job, styleForThisJob, playbackSpeedForThisJob);
            } else {
                saveJobToFile(job, styleForThisJob);
            }
            std::lock_guard<std::mutex> guard(mailbox.lock);
            mailbox.isSavingAFile = false;
        }
    });

    std::string commandLine;
    while (std::getline(std::cin, commandLine)) {
        const bool isSpeakCommand = commandLine.rfind("speak ", 0) == 0;
        const bool isSaveCommand = commandLine.rfind("save ", 0) == 0;
        if (isSpeakCommand || isSaveCommand) {
            std::lock_guard<std::mutex> guard(mailbox.lock);
            if (mailbox.isSavingAFile) {
                sendMessageToApp("{\"type\":\"busy\",\"message\":\"Rvoice is still making an audio file. Cancel it or wait until it is done.\"}");
                continue;
            }
            shouldStopCurrentJob = true;
            mailbox.waitingJobs.push_back(speechJobFromCommandArguments(commandLine.substr(isSpeakCommand ? 6 : 5), isSpeakCommand));
            /* Marked busy right away, not when the thread starts the job, so a "speak" sent a moment later cannot replace it. */
            mailbox.isSavingAFile = isSaveCommand;
            mailbox.newJobArrived.notify_one();
        } else if (commandLine.rfind("style ", 0) == 0) {
            const std::string styleText = commandLine.substr(6);
            const size_t firstTab = styleText.find('\t');
            const size_t secondTab = firstTab == std::string::npos ? std::string::npos : styleText.find('\t', firstTab + 1);
            if (secondTab != std::string::npos) {
                const std::string voice = styleText.substr(secondTab + 1);
                std::lock_guard<std::mutex> guard(mailbox.lock);
                mailbox.style.languageCode = styleText.substr(0, firstTab);
                mailbox.style.languageName = styleText.substr(firstTab + 1, secondTab - firstTab - 1);
                mailbox.style.voice = voice == "male" ? "male" : "female";
            }
        } else if (commandLine.rfind("speed ", 0) == 0) {
            const double requestedSpeed = std::max(0.5, std::min(2.0, std::atof(commandLine.substr(6).c_str())));
            {
                std::lock_guard<std::mutex> guard(mailbox.lock);
                mailbox.playbackSpeed = requestedSpeed;
            }
            std::lock_guard<std::mutex> guard(currentOutputLock);
            if (currentSpeakerOutput) {
                currentSpeakerOutput->setPlaybackSpeed(requestedSpeed);
            }
        } else if (commandLine == "pause" || commandLine == "resume" || commandLine.rfind("rewind ", 0) == 0) {
            std::lock_guard<std::mutex> guard(currentOutputLock);
            if (!currentSpeakerOutput) {
                continue;
            }
            if (commandLine == "pause") {
                currentSpeakerOutput->pause();
                sendMessageToApp("{\"type\":\"paused\"}");
            } else if (commandLine == "resume") {
                currentSpeakerOutput->resume();
                sendMessageToApp("{\"type\":\"resumed\"}");
            } else {
                currentSpeakerOutput->jumpBack(std::atof(commandLine.substr(7).c_str()));
            }
        } else if (commandLine == "stop") {
            std::lock_guard<std::mutex> guard(mailbox.lock);
            if (!mailbox.isSavingAFile) {
                shouldStopCurrentJob = true;
            }
        } else if (commandLine == "cancel-save") {
            std::lock_guard<std::mutex> guard(mailbox.lock);
            if (mailbox.isSavingAFile) {
                shouldStopCurrentJob = true;
            }
        } else if (commandLine == "quit") {
            break;
        }
    }
    {
        std::lock_guard<std::mutex> guard(mailbox.lock);
        mailbox.shouldQuit = true;
        shouldStopCurrentJob = true;
        mailbox.newJobArrived.notify_one();
    }
    speakingThread.join();
    mtmd_free(loaded.audioContext);
    llama_sampler_free(loaded.tokenPicker);
    llama_free(loaded.context);
    llama_model_free(loaded.model);
    llama_backend_free();
    return 0;
}
