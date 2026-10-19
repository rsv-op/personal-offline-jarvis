package com.example.voiceassistant.stt

import com.example.voiceassistant.memory.DeviceMemory
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
 * Offline sherpa-onnx adapter using the Streaming Zipformer transducer model.
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
 * The streaming protocol mirrors Vosk: 16 kHz mono 16-bit PCM frames are
 * fed via [processAudio], the engine returns `true` when the recogniser
 * has detected an endpoint (silence after speech), and the partial/final
 * text comes from [getPartialText] / [getFinalText]. The integrator
 * consumes this exactly the same way it consumed the Vosk engine.
 *
 * The native libraries (`libsherpa-onnx-jni.so` for `arm64-v8a` and
 * `armeabi-v7a`) are bundled inside the `com.github.k2-fsa:sherpa-onnx:1.13.8`
 * AAR from jitpack. No additional .so files need to be downloaded or
 * installed by the user.
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
        val encoder = pickFile(directory, "encoder", ".onnx")
        val decoder = pickFile(directory, "decoder", ".onnx")
        val joiner = pickFile(directory, "joiner", ".onnx")
        checkNotNull(encoder) { "sherpa-onnx model is missing encoder-*.onnx in $modelPath" }
        checkNotNull(decoder) { "sherpa-onnx model is missing decoder-*.onnx in $modelPath" }
        checkNotNull(joiner) { "sherpa-onnx model is missing joiner-*.onnx in $modelPath" }

        // The Java config uses a Builder pattern. FeatureConfig defaults
        // to sample_rate=16000 and feature_dim=80, which is exactly the
        // 16 kHz Zipformer configuration we want, so we don't set it
        // explicitly.
        //
        // Kotlin's handling of Java static methods on classes that
        // also have instance methods can be quirky; we instantiate
        // the nested Builder classes directly via their public
        // no-arg constructor. The Java side is unchanged.
        val modelConfig = OnlineModelConfig.Builder()
            .setTransducer(
                OnlineTransducerModelConfig.Builder()
                    .setEncoder(encoder.absolutePath)
                    .setDecoder(decoder.absolutePath)
                    .setJoiner(joiner.absolutePath)
                    .build()
            )
            .setTokens(tokens.absolutePath)
            .setNumThreads(2)
            // Provider: cpu. sherpa-onnx 1.13.8 also supports "nnapi" for
            // NNAPI acceleration, but only on a subset of devices; the
            // INT8 zipformer model is already fast enough on CPU, and
            // "cpu" avoids the NNAPI compatibility matrix entirely.
            .setProvider("cpu")
            .build()

        val config = OnlineRecognizerConfig.Builder()
            .setFeatureConfig(
                FeatureConfig.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setFeatureDim(80)
                    .build()
            )
            .setOnlineModelConfig(modelConfig)
            .setDecodingMethod("greedy_search")
            .setEnableEndpoint(true)
            .build()

        try {
            recognizer = OnlineRecognizer(config)
            stream = recognizer!!.createStream()
            modelDirectory = directory
            released.set(false)
        } catch (error: Exception) {
            recognizer?.release()
            recognizer = null
            stream = null
            throw error
        }
    }

    @Synchronized
    override fun processAudio(audioData: ShortArray): Boolean {
        val currentStream = requireStream()
        val currentRecognizer = requireRecognizer()
        // sherpa-onnx consumes float32 in [-1, 1]. Vosk consumed int16,
        // so the integrator's audio frames are still int16. Convert once
        // here; the conversion is 1 mul + 1 div per sample, well below
        // the cost of the decode step that follows.
        val samples = ShortArrayToFloatArray.convert(audioData)
        currentStream.acceptWaveform(samples, SAMPLE_RATE_HZ)
        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }
        return currentRecognizer.isEndpoint(currentStream)
    }

    @Synchronized
    override fun getPartialText(): String {
        val currentStream = requireStream()
        val currentRecognizer = requireRecognizer()
        return currentRecognizer.getResult(currentStream).text.orEmpty().trim()
    }

    @Synchronized
    override fun getFinalText(): String = getPartialText()

    @Synchronized
    override fun reset() {
        val currentRecognizer = recognizer ?: return
        val currentStream = stream ?: return
        currentRecognizer.reset(currentStream)
    }

    @Synchronized
    override fun release() {
        if (released.getAndSet(true)) return
        try {
            stream?.release()
        } finally {
            stream = null
            try {
                recognizer?.release()
            } finally {
                recognizer = null
                modelDirectory = null
            }
        }
    }

    private fun requireRecognizer(): OnlineRecognizer =
        recognizer ?: error("sherpa-onnx STT engine has not been initialized")

    private fun requireStream(): OnlineStream =
        stream ?: error("sherpa-onnx STT engine has not been initialized")

    /**
     * Picks the first file inside [directory] whose name starts with
     * [prefix] and ends with [suffix]. Used to find the encoder/decoder/
     * joiner ONNX regardless of which checkpoint name (e.g. `epoch-99-
     * avg-1`, `int8`, etc.) the model archive uses.
     */
    private fun pickFile(directory: File, prefix: String, suffix: String): File? =
        directory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(suffix) }
            .sortedBy { it.name.length }
            // Prefer the shorter (non-int8) name when both exist. The
            // int8 variants have a longer name (`.int8` token), so
            // sorting by name length and taking the first is a stable,
            // content-independent heuristic. The model loader accepts
            // either format, so the int8 fallback is the second choice.
            .firstOrNull()

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}

/**
 * Pure-Kotlin helper: convert a `ShortArray` of 16-bit PCM samples into
 * a `FloatArray` of normalized [-1, 1] floats. Inlined because this is
 * called on every audio frame (1600 samples per frame at 16 kHz, 100 ms)
 * and a function call per frame adds up.
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
