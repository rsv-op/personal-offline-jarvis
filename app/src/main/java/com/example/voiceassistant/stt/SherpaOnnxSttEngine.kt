package com.example.voiceassistant.stt

import android.util.Log
import com.example.voiceassistant.memory.DeviceMemory
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Offline sherpa-onnx adapter using the Streaming Zipformer (transducer) model.
 *
 * Replaces the Vosk engine (PR #27). The [SttEngine] contract is unchanged so
 * the [OfflineSttIntegrator] does not need to be modified.
 *
 * Model layout on disk (after [SherpaOnnxModelStore] copies it into private
 * app storage):
 *
 * ```
 * <modelDir>/
 *   tokens.txt
 *   encoder-epoch-99-avg-1.onnx       (or encoder-epoch-99-avg-1.int8.onnx)
 *   decoder-epoch-99-avg-1.onnx       (or decoder-epoch-99-avg-1.int8.onnx)
 *   joiner-epoch-99-avg-1.onnx        (or joiner-epoch-99-avg-1.int8.onnx)
 * ```
 *
 * INT8 variants are preferred when present: they halve the on-disk model
 * size with no measurable accuracy loss on the 20M English model, and they
 * fit comfortably under the 1.5 GB free-RAM budget on the target device.
 *
 * ## Why the builder chain (and why positional arguments)
 *
 * The classes the AAR ships are **Java** classes generated from
 * `sherpa-onnx/java-api` at tag `v1.13.8`: every config is immutable and carries a
 * nested `Builder` (`FeatureConfig.builder().setSampleRate(…).build()`), and the
 * builders expose setter-style method names. Kotlin therefore has to call them
 * positionally — `FeatureConfig(sampleRate = 16_000)` is not a constructor that
 * exists, and named arguments on a Java method are a compile error. The Kotlin
 * `kotlin-api` sources in the same repository are *samples*, not the published
 * artefact; they are not on the classpath of this module.
 *
 * ## Streaming protocol
 *
 * 16 kHz mono 16-bit PCM frames arrive through [processAudio]. They are
 * converted to the float32 `[-1, 1]` range sherpa-onnx expects, fed into the
 * live [OnlineStream], and the recogniser is drained until it is no longer
 * ready. `true` is returned when an endpoint (silence after speech) has been
 * detected, which is the same signal the Vosk engine produced, so
 * [OfflineSttIntegrator] consumes it unchanged.
 *
 * The native libraries (`libsherpa-onnx-jni.so`) ship inside the
 * `com.github.k2-fsa:sherpa-onnx:1.13.8` AAR; the `extractSherpaJniLibs` task
 * in `app/build.gradle.kts` unpacks them into `build/sherpa-jni` at configure
 * time and packs the `arm64-v8a` slice into the APK, so nothing is downloaded
 * at runtime.
 */
class SherpaOnnxSttEngine @Inject constructor(
    private val memoryBudget: DeviceMemory
) : SttEngine {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var modelDirectory: File? = null
    private val released = AtomicBoolean(false)

    @Synchronized
    override fun initialize(modelPath: String) {
        release()
        val directory = File(modelPath)

        // Validate the *contents*, not just the file names. The previous
        // Vosk-era bug — an import that wrote empty files passing the
        // "does conf/model.conf exist" check — had the same root cause as
        // any "the model files exist but the loader crashes" failure: the
        // file existence check happened before the size check, and the
        // size check happened after a half-baked extraction. We do the
        // size check first and surface a specific reason so the user can
        // act on it.
        val problem = SherpaOnnxModelValidator.describeModelProblem(directory)
        require(problem == null) { "Not a usable sherpa-onnx model at $modelPath: $problem" }

        // Memory headroom: the int8 20M model is ~42 MB on disk and
        // ~120 MB in RAM once the encoder/decoder/joiner ONNX sessions
        // are loaded. 1.5× headroom keeps us safe on a phone with 1.5 GB
        // free RAM, which is the budget the rest of the app is sized for.
        val modelBytes = SherpaOnnxModelValidator.modelSizeBytes(directory)
        memoryBudget.refusalReason(modelBytes, headroomMultiplier = 1.5)?.let { reason ->
            throw IllegalStateException(reason)
        }

        val tokens = File(directory, "tokens.txt")
        val encoder = pickFile(directory, "encoder")
        val decoder = pickFile(directory, "decoder")
        val joiner = pickFile(directory, "joiner")
        checkNotNull(encoder) { "sherpa-onnx model is missing encoder-*.onnx in $modelPath" }
        checkNotNull(decoder) { "sherpa-onnx model is missing decoder-*.onnx in $modelPath" }
        checkNotNull(joiner) { "sherpa-onnx model is missing joiner-*.onnx in $modelPath" }
        check(tokens.isFile) { "sherpa-onnx model is missing tokens.txt in $modelPath" }

        // 16 kHz / 80-bin log-mel features: the exact front-end the
        // gigaspeech / librispeech zipformer transducers were trained on.
        // Dither is left at 0 — we feed already-clean device microphone
        // audio, and dithering a live signal costs accuracy.
        val featureConfig = FeatureConfig.builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setFeatureDim(FEATURE_DIM)
            .setDither(0.0f)
            .build()

        val transducer = OnlineTransducerModelConfig.builder()
            .setEncoder(encoder.absolutePath)
            .setDecoder(decoder.absolutePath)
            .setJoiner(joiner.absolutePath)
            .build()

        val modelConfig = OnlineModelConfig.builder()
            .setTransducer(transducer)
            .setTokens(tokens.absolutePath)
            // Two threads is the sweet spot on a mid-range ARM64 phone: the
            // int8 zipformer decodes ~8× real time on two cores and leaves the
            // rest for llama.cpp when the user is mid-conversation.
            .setNumThreads(2)
            // "cpu" only. 1.13.8 also understands "nnapi"/"xnnpack", but the
            // NNAPI compatibility matrix is a support burden with no benefit
            // for a 20M-parameter streaming model, and the app is
            // deliberately CPU-only so decoding is deterministic.
            .setProvider("cpu")
            .setDebug(false)
            .setModelType("transducer")
            .build()

        // Endpointing is what tells [OfflineSttIntegrator] the utterance
        // finished. Rule 1 fires on 2.4 s of trailing silence (a real pause),
        // rule 2 on 1.2 s once speech has been heard (the normal end of a
        // command), and rule 3 caps a monologue at 20 s so an open microphone
        // cannot grow an utterance without bound.
        val endpointConfig = EndpointConfig.builder()
            .setRule1(
                EndpointRule.builder()
                    .setMustContainNonSilence(false)
                    .setMinTrailingSilence(2.4f)
                    .setMinUtteranceLength(0.0f)
                    .build()
            )
            .setRule2(
                EndpointRule.builder()
                    .setMustContainNonSilence(true)
                    .setMinTrailingSilence(1.2f)
                    .setMinUtteranceLength(0.0f)
                    .build()
            )
            .setRule3(
                EndpointRule.builder()
                    .setMustContainNonSilence(false)
                    .setMinTrailingSilence(0.0f)
                    .setMinUtteranceLength(20.0f)
                    .build()
            )
            .build()

        val config = OnlineRecognizerConfig.builder()
            .setFeatureConfig(featureConfig)
            .setOnlineModelConfig(modelConfig)
            .setEndpointConfig(endpointConfig)
            // Greedy search is the low-latency streaming decoder. Modified
            // beam search needs complete utterances, which a hands-free loop
            // never has.
            .setDecodingMethod("greedy_search")
            .setEnableEndpoint(true)
            .build()

        try {
            val created = OnlineRecognizer(config)
            recognizer = created
            stream = created.createStream()
            modelDirectory = directory
            released.set(false)
            Log.i(TAG, "sherpa-onnx recogniser ready at ${directory.absolutePath}")
        } catch (error: Exception) {
            // A half-initialised recogniser is worse than none: calls into
            // the native layer with a null handle abort the process. Tear
            // everything down and let the integrator surface the failure.
            runCatching { recognizer?.release() }
            recognizer = null
            stream = null
            modelDirectory = null
            released.set(true)
            throw error
        }
    }

    @Synchronized
    override fun processAudio(audioData: ShortArray): Boolean {
        val currentStream = requireStream()
        val currentRecognizer = requireRecognizer()
        if (audioData.isEmpty()) return false

        // sherpa-onnx consumes float32 in [-1, 1]; Vosk consumed int16, so
        // the integrator still hands us int16 frames. Convert once here —
        // one multiply per sample, which is negligible next to the decode
        // step that follows.
        currentStream.acceptWaveform(ShortArrayToFloatArray.convert(audioData), SAMPLE_RATE_HZ)

        // Drain the recogniser. Streaming zipformer keeps a chunk buffer,
        // and `isReady` is true whenever a new chunk is available, so this
        // loop runs 0–2 times per 100 ms frame.
        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }
        return currentRecognizer.isEndpoint(currentStream)
    }

    @Synchronized
    override fun getPartialText(): String {
        // Deliberately no null-returns here: a caller that asks for text with no
        // model loaded has a bug, and a silent "" would hide it behind an empty
        // transcript. The previous Vosk-era engine threw for the same reason, and
        // `SherpaOnnxSttEngineTest` pins it: "a refused model leaves the engine
        // uninitialized" expects a loud failure, not a blank string.
        val currentStream = requireStream()
        val currentRecognizer = requireRecognizer()
        return runCatching { currentRecognizer.getResult(currentStream).text.orEmpty().trim() }
            .getOrDefault("")
    }

    @Synchronized
    override fun getFinalText(): String = getPartialText()

    @Synchronized
    override fun reset() {
        val currentRecognizer = recognizer ?: return
        val currentStream = stream ?: return
        // The stream resets its own feature buffer and decoder state, which
        // is cheaper (and safer) than tearing down the stream object.
        runCatching { currentRecognizer.reset(currentStream) }
    }

    @Synchronized
    override fun release() {
        if (released.getAndSet(true)) return
        try {
            runCatching { stream?.release() }
        } finally {
            stream = null
            try {
                runCatching { recognizer?.release() }
            } finally {
                recognizer = null
                modelDirectory = null
            }
        }
    }

    /** Absolute path of the loaded model directory, or null when released. */
    @Synchronized
    fun loadedModelDirectory(): String? = modelDirectory?.absolutePath

    private fun requireRecognizer(): OnlineRecognizer =
        recognizer ?: error("sherpa-onnx STT engine has not been initialized")

    private fun requireStream(): OnlineStream =
        stream ?: error("sherpa-onnx STT engine has not been initialized")

    /**
     * Picks a model file inside [directory] whose name starts with [prefix]
     * and ends with `.onnx`.
     *
     * INT8 files are preferred: they are ~4× smaller on disk and, for the
     * 20M streaming zipformer, decode with the same word error rate. Ties
     * are broken by file size (larger first) so the non-int8 file is only
     * selected when no int8 variant exists.
     */
    private fun pickFile(directory: File, prefix: String): File? =
        directory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            .sortedWith(
                compareByDescending<File> { it.name.contains("int8", ignoreCase = true) }
                    .thenByDescending { it.length() }
            )
            .firstOrNull()

    private companion object {
        const val TAG = "SherpaOnnxSttEngine"
        const val SAMPLE_RATE_HZ = 16_000
        const val FEATURE_DIM = 80
    }
}

/**
 * Pure-Kotlin helper: convert a `ShortArray` of 16-bit PCM samples into a
 * `FloatArray` of normalized [-1, 1] floats.
 *
 * The divisor is 32768 (2^15) rather than 32767 so the negative rail
 * (-32768 -> exactly -1.0) is symmetric with the positive rail, which is what
 * the ONNX front-end expects. Inlined per sample because this runs on every
 * audio frame (1600 samples per 100 ms frame at 16 kHz).
 */
private object ShortArrayToFloatArray {
    fun convert(samples: ShortArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = samples[i] / 32768.0f
        }
        return out
    }
}
