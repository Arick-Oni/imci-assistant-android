package org.imci.transcriber

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * BanglaBERT ensemble for the yes/no form questions: (question, transcript window) -> P(yes), P(no), P(not discussed),
 * averaged over every bb*.onnx (int8) in [dir] - single runs disagree, the average is stable (Kaggle job
 * imci-banglabert-ensemble). Runs on the phone's CPU through ONNX Runtime, a few hundred milliseconds per window.
 */
class BertClassifier(dir: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    val tokenizer = BertTokenizer(File(dir, "tokenizer"))
    private val sessions: List<OrtSession>
    val size get() = sessions.size

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        sessions = dir.listFiles { f -> f.name.startsWith("bb") && f.extension == "onnx" }.orEmpty().sortedBy { it.name }
            .map { env.createSession(it.absolutePath, opts) }
        check(sessions.isNotEmpty()) { "no BanglaBERT models (bb*.onnx) in $dir" }
    }

    /** For each window text: averaged [yes, no, not discussed] probabilities for [question]. */
    fun classify(question: String, windows: List<String>): List<FloatArray> {
        val enc = windows.map { tokenizer.encodePair(question, it) }
        val t = enc.maxOf { it.first.size }
        val n = enc.size
        val ids = LongArray(n * t) { tokenizer.pad.toLong() }
        val mask = LongArray(n * t)
        val types = LongArray(n * t)
        enc.forEachIndexed { i, (a, b) -> for (j in a.indices) { ids[i * t + j] = a[j].toLong(); mask[i * t + j] = 1; types[i * t + j] = b[j].toLong() } }
        val shape = longArrayOf(n.toLong(), t.toLong())
        val sum = Array(n) { FloatArray(3) }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { tIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { tMask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape).use { tTypes ->
                    for (s in sessions) {
                        s.run(mapOf("input_ids" to tIds, "attention_mask" to tMask, "token_type_ids" to tTypes)).use { r ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = r[0].value as Array<FloatArray>
                            for (i in 0 until n) {
                                val l = logits[i]; val mx = l.max()
                                val e = l.map { exp((it - mx).toDouble()) }; val z = e.sum()
                                for (c in 0..2) sum[i][c] += (e[c] / z).toFloat() / sessions.size
                            }
                        }
                    }
                }
            }
        }
        return sum.toList()
    }

    /** Tokenizer check against Python (tokenizer_cases.json from the export): number of cases that differ, -1 = no file. */
    fun selfTest(dir: File): Int {
        val f = File(dir, "tokenizer_cases.json").takeIf { it.exists() } ?: return -1
        val cases = org.json.JSONArray(f.readText())
        var bad = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("input_ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val (ids, _) = tokenizer.encodePair(c.getString("question"), c.getString("window"))
            if (!ids.contentEquals(want)) {
                bad++
                android.util.Log.w("imci_bert", "tokenizer case $i differs: got ${ids.size} ids, want ${want.size}; first diff at " +
                    (ids.indices.firstOrNull { it >= want.size || ids[it] != want[it] } ?: ids.size))
            }
        }
        return bad
    }

    override fun close() { sessions.forEach { it.close() } }
}
