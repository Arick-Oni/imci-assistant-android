package org.imci.transcriber

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records 16 kHz mono PCM straight from the microphone, keeps it in memory
 * (readable while recording, for live mode) and saves it as a WAV file on stop.
 */
class Recorder {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val lock = Any()
    private var pcm = ShortArray(AudioDecoder.SAMPLE_RATE * 60)
    private var count = 0

    val isRecording get() = running.get()
    /** Loudness of the newest buffer, 0 (silence, -55 dBFS) .. 1 (loud speech, -15 dBFS), for the voice animation. */
    @Volatile var level = 0f; private set
    /** Paused: the microphone keeps running but nothing is kept, so the transcript and the timer stop. */
    private val paused = AtomicBoolean(false)
    val isPaused get() = paused.get()
    fun pause(on: Boolean) { paused.set(on); if (on) level = 0f }
    val sampleCount get() = synchronized(lock) { count }
    val seconds get() = sampleCount / AudioDecoder.SAMPLE_RATE.toFloat()

    @SuppressLint("MissingPermission")  // checked by the UI before start()
    fun start() {
        if (running.getAndSet(true)) return
        paused.set(false)
        synchronized(lock) { count = 0 }
        val minBuf = AudioRecord.getMinBufferSize(
            AudioDecoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioDecoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        worker = thread(name = "recorder", priority = Thread.MAX_PRIORITY) {
            val buf = ShortArray(minBuf / 2)
            rec.startRecording()
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (paused.get()) continue
                if (n > 0) {
                    var sq = 0.0
                    for (i in 0 until n) { val s = buf[i] / 32768.0; sq += s * s }
                    val db = 10 * kotlin.math.log10(sq / n + 1e-10)
                    level = ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
                }
                if (n > 0) synchronized(lock) {
                    if (count + n > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, count + n))
                    System.arraycopy(buf, 0, pcm, count, n)
                    count += n
                }
            }
            rec.stop(); rec.release()
            level = 0f
        }
    }

    /**
     * Test mode: plays [src] (16 kHz) into the buffer at real-time speed, 100 ms at a time, as if the microphone
     * heard it - live transcription and live form filling then run exactly as in a visit. [onEnd] runs on the
     * replay thread when the audio is used up (not when stopped early).
     */
    fun startReplay(src: FloatArray, onEnd: () -> Unit) {
        if (running.getAndSet(true)) return
        paused.set(false)
        synchronized(lock) { count = 0 }
        worker = thread(name = "replay", priority = Thread.MAX_PRIORITY) {
            val step = AudioDecoder.SAMPLE_RATE / 10
            var t0 = android.os.SystemClock.elapsedRealtime()
            var i = 0
            while (running.get() && i < src.size) {
                if (paused.get()) { Thread.sleep(100); t0 += 100; continue }
                val n = minOf(step, src.size - i)
                var sq = 0.0
                for (j in 0 until n) { val s = src[i + j].toDouble(); sq += s * s }
                level = ((10 * kotlin.math.log10(sq / n + 1e-10) + 55) / 40).toFloat().coerceIn(0f, 1f)
                synchronized(lock) {
                    if (count + n > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, count + n))
                    for (j in 0 until n) pcm[count + j] = (src[i + j].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    count += n
                }
                i += n
                val wait = t0 + i * 1000L / AudioDecoder.SAMPLE_RATE - android.os.SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            level = 0f
            if (i >= src.size && running.get()) onEnd()
        }
    }

    /** Samples [from, to) as floats - safe to call while recording. */
    fun samples(from: Int = 0, to: Int = sampleCount): FloatArray = synchronized(lock) {
        val a = from.coerceIn(0, count)
        val b = to.coerceIn(a, count)
        FloatArray(b - a) { pcm[a + it] / 32768f }
    }

    /** Stops recording, writes [wavFile] (if given) and returns all samples as floats. */
    fun stop(wavFile: File?): FloatArray {
        running.set(false)
        worker?.join()
        if (wavFile != null) synchronized(lock) { writeWav(wavFile) }
        return samples()
    }

    private fun writeWav(file: File) {
        file.parentFile?.mkdirs()
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm, 0, count)
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(AudioDecoder.SAMPLE_RATE).putInt(AudioDecoder.SAMPLE_RATE * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(count * 2)
            f.write(h.array())
            f.write(bytes.array())
        }
    }
}
