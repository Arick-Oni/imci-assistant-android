package org.imci.transcriber

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

data class Chunk(val start: Float, val end: Float, val text: String, val loopCollapsed: Boolean)

/**
 * A selectable speech model: the Whisper inside the APK ([file] = null), a whisper.cpp .bin in the models folder,
 * or a NeMo CTC model for sherpa-onnx ([tokens] set): a folder in models/ holding <name>.onnx + tokens.txt
 * (e.g. Bhatiyali / Srotoswini exported with bn_distil_v1/models/bhatiyali/export_sherpa*.py).
 */
data class SpeechModel(val id: String, val label: String, val file: File?, val tokens: File? = null) {
    val isCtc get() = tokens != null
}

/**
 * On-device version of imci_v3 (without speaker labels):
 *   Silero VAD -> speech regions merged into <= MAX_CHUNK_S chunks
 *   -> Bengali Whisper, built-in regional medium or a chosen .bin (greedy, no fallback, no timestamps)
 *      with the encoder window (audio_ctx) sized to each chunk,
 *      or a NeMo CTC model (Bhatiyali, Srotoswini) through sherpa-onnx: one pass per chunk, no punctuation
 *   -> same text cleanup as the PC pipeline.
 */
class Transcriber(private val context: Context) {
    companion object {
        const val MAX_CHUNK_S = 20f
        // Both ship inside the APK (stored uncompressed, read in place - no copy to storage):
        // speech_model.bin = regional Bengali Whisper medium, q8_0; vad_model.bin = Silero VAD v6.2
        const val MODEL_ASSET = "speech_model.bin"
        const val VAD_ASSET = "vad_model.bin"
        const val BUILTIN = "builtin"

        @Volatile private var backendsLoaded = false
    }

    private var ctx = 0L                              // whisper.cpp model
    private var ctc: OfflineRecognizer? = null        // sherpa-onnx model
    private var vctx = 0L
    val isLoaded get() = (ctx != 0L || ctc != null) && vctx != 0L

    /** Extra models: any whisper.cpp .bin copied to Android/data/org.imci.transcriber/files/models/. */
    val modelsDir: File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    var model: SpeechModel? = null
        private set

    fun availableModels(): List<SpeechModel> {
        val whisper = modelsDir.listFiles { f -> f.extension == "bin" }.orEmpty().sortedBy { it.name }.map {
            SpeechModel(it.name, "${it.nameWithoutExtension} (${it.length() / 1_000_000} MB)", it)
        }
        val ctcModels = modelsDir.listFiles { f -> f.isDirectory && File(f, "tokens.txt").exists() }.orEmpty()
            .sortedBy { it.name }.flatMap { dir ->
                dir.listFiles { f -> f.extension == "onnx" }.orEmpty().sortedBy { it.name }.map {
                    SpeechModel("${dir.name}/${it.name}", "${dir.name} · ${it.nameWithoutExtension} (${it.length() / 1_000_000} MB, fast)",
                        it, File(dir, "tokens.txt"))
                }
            }
        // slim builds (for sending over WhatsApp/Drive) leave the 820 MB built-in model out of the APK
        val builtin = runCatching { context.assets.open(MODEL_ASSET).close() }.isSuccess
        return (if (builtin) listOf(SpeechModel(BUILTIN, "Built-in (regional medium)", null)) else emptyList()) + whisper + ctcModels
    }

    /** Number of "big" cores: cores whose max frequency is above the slowest cluster's. */
    fun defaultThreads(): Int = runCatching {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        }
        val big = freqs.count { it > (freqs.minOrNull() ?: 0) }
        if (big in 2..8) big else 4
    }.getOrDefault(4)

    /** ggml CPU backends (shared by speech and form filling), loaded once. */
    fun ensureBackends() {
        if (!backendsLoaded) {
            WhisperNative.loadBackends(context.applicationInfo.nativeLibraryDir)
            backendsLoaded = true
        }
    }

    /** Loads the speech detector and [m]; a different model than the loaded one replaces it. */
    fun load(m: SpeechModel) {
        ensureBackends()
        if (vctx == 0L) {
            vctx = WhisperNative.initVadFromAsset(context.assets, VAD_ASSET, 2)
            check(vctx != 0L) { "Could not start the speech detector" }
        }
        if (model != m) freeSpeechModel()
        if (model == null) {
            if (m.isCtc) {
                ctc = runCatching {
                    OfflineRecognizer(config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = AudioDecoder.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(model = m.file!!.absolutePath),
                            tokens = m.tokens!!.absolutePath, numThreads = defaultThreads(), provider = "cpu"),
                    ))
                }.getOrNull()
                check(ctc != null) { "Could not load the speech model ${m.label}" }
            } else {
                ctx = if (m.file == null) WhisperNative.initModelFromAsset(context.assets, MODEL_ASSET)
                      else WhisperNative.initModel(m.file.absolutePath)
                check(ctx != 0L) { "Could not load the speech model ${m.label}" }
            }
            model = m
        }
    }

    private fun freeSpeechModel() {
        if (ctx != 0L) WhisperNative.freeModel(ctx)
        ctc?.release()
        ctx = 0L; ctc = null; model = null
    }

    /** Speech regions merged into chunks of at most MAX_CHUNK_S seconds. */
    fun chunks(samples: FloatArray): List<Pair<Float, Float>> {
        val seg = WhisperNative.vadSegments(vctx, samples, MAX_CHUNK_S)
        val out = ArrayList<Pair<Float, Float>>()
        var i = 0
        while (i + 1 < seg.size) {
            val s = seg[i]; val e = seg[i + 1]
            val last = out.lastOrNull()
            if (last != null && e - last.first <= MAX_CHUNK_S) out[out.size - 1] = last.first to e
            else out.add(s to e)
            i += 2
        }
        return out
    }

    /** Speech regions in [samples] (seconds, relative to its start) without forced splitting - for live mode. */
    fun speechRegions(samples: FloatArray): List<Pair<Float, Float>> {
        val seg = WhisperNative.vadSegments(vctx, samples, 1000f)
        return (0 until seg.size / 2).map { seg[2 * it] to seg[2 * it + 1] }
    }

    /** Encoder window: 50 frames/s, rounded up to 64, +64 margin (validated on the Poco F5). */
    private fun audioCtx(seconds: Float) = min(1500, ceil(seconds * 50 / 64).toInt() * 64 + 64)

    /** Transcribes one chunk of audio; [start]/[end] are only stored on the result. Null if no text. */
    fun transcribeChunk(audio: FloatArray, start: Float, end: Float, threads: Int): Chunk? {
        if (audio.isEmpty()) return null
        val raw = ctc?.let { rec ->
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(audio, AudioDecoder.SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text
            } finally { stream.release() }
        } ?: TextCleanup.decode(WhisperNative.transcribe(ctx, audio, threads, audioCtx(audio.size / AudioDecoder.SAMPLE_RATE.toFloat())))
        val (text, removed) = TextCleanup.clean(raw)
        return if (text.isNotEmpty() && !cancelled) Chunk(start, end, text, removed > 0) else null
    }

    fun transcribe(
        samples: FloatArray,
        threads: Int,
        onChunk: (index: Int, total: Int, chunk: Chunk?) -> Unit,
    ): List<Chunk> {
        cancelled = false
        val regions = chunks(samples)
        val result = ArrayList<Chunk>()
        onChunk(0, regions.size, null)
        for ((idx, region) in regions.withIndex()) {
            if (cancelled) break
            val (st, en) = region
            val a = (st * AudioDecoder.SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val b = (en * AudioDecoder.SAMPLE_RATE).toInt().coerceIn(a, samples.size)
            val chunk = transcribeChunk(samples.copyOfRange(a, b), st, en, threads)
            if (cancelled) break
            chunk?.let { result.add(it) }
            onChunk(idx + 1, regions.size, chunk)
        }
        return result
    }

    fun resetCancel() { cancelled = false }

    @Volatile var cancelled = false
        private set

    fun cancel() {
        cancelled = true
        WhisperNative.cancel()
    }

    fun release() {
        freeSpeechModel()
        if (vctx != 0L) WhisperNative.freeVad(vctx)
        vctx = 0L
    }
}
