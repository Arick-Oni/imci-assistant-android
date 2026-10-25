package org.imci.transcriber

import java.io.File
import java.text.Normalizer

/**
 * BERT WordPiece tokenizer (port of Hugging Face BertTokenizer / ElectraTokenizer) for BanglaBERT:
 * clean text -> (optional lower-casing + accent stripping) -> split on whitespace and punctuation -> greedy
 * longest-match WordPiece with "##" continuations. Pairs are encoded as [CLS] a [SEP] b [SEP] with the second text
 * truncated to fit [maxLen] (truncation="only_second"), token type 0 for the first part and 1 for the second.
 * Checked against the Python tokenizer with tokenizer_cases.json from the Kaggle export.
 */
class BertTokenizer(dir: File) {
    private val vocab: Map<String, Int>
    private val lower: Boolean
    private val stripAccents: Boolean
    val cls: Int; val sep: Int; val pad: Int; private val unk: Int

    init {
        vocab = File(dir, "vocab.txt").readLines().withIndex().associate { (i, t) -> t to i }
        val cfg = File(dir, "tokenizer_config.json").takeIf { it.exists() }?.readText()?.let { org.json.JSONObject(it) }
        lower = cfg?.optBoolean("do_lower_case", false) ?: false
        stripAccents = cfg?.let { if (it.isNull("strip_accents") || !it.has("strip_accents")) lower else it.optBoolean("strip_accents") } ?: lower
        cls = vocab.getValue("[CLS]"); sep = vocab.getValue("[SEP]"); pad = vocab["[PAD]"] ?: 0; unk = vocab.getValue("[UNK]")
    }

    fun tokenize(text: String): List<Int> = basic(text).flatMap { wordPiece(it) }

    /** ids and token-type ids for the pair (a, b), b cut to fit [maxLen]. */
    fun encodePair(a: String, b: String, maxLen: Int = 512): Pair<IntArray, IntArray> {
        val ta = tokenize(a)
        val tb = tokenize(b).let { it.take(maxOf(0, maxLen - ta.size - 3)) }
        val ids = listOf(cls) + ta + sep + tb + sep
        val types = List(ta.size + 2) { 0 } + List(tb.size + 1) { 1 }
        return ids.toIntArray() to types.toIntArray()
    }

    private fun basic(text: String): List<String> {
        val sb = StringBuilder()
        for (ch in text) {
            val c = ch.code
            if (c == 0 || c == 0xFFFD || (Character.isISOControl(ch) && ch !in "\t\n\r")) continue
            when {
                Character.isWhitespace(ch) || Character.getType(ch) == Character.SPACE_SEPARATOR.toInt() -> sb.append(' ')
                isCjk(c) -> sb.append(' ').append(ch).append(' ')
                else -> sb.append(ch)
            }
        }
        val out = ArrayList<String>()
        for (w0 in sb.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var w = w0
            if (lower) w = w.lowercase()
            if (stripAccents) w = Normalizer.normalize(w, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            val cur = StringBuilder()
            for (ch in w) {
                if (isPunct(ch)) { if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }; out.add(ch.toString()) }
                else cur.append(ch)
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out
    }

    private fun wordPiece(w: String): List<Int> {
        if (w.length > 100) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < w.length) {
            var end = w.length
            var id: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + w.substring(start, end)
                id = vocab[sub]
                if (id != null) break
                end--
            }
            if (id == null) return listOf(unk)
            out.add(id); start = end
        }
        return out
    }

    private fun isPunct(ch: Char): Boolean {
        val c = ch.code
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        return when (Character.getType(ch).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0xF900..0xFAFF
}
