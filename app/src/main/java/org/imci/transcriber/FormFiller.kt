package org.imci.transcriber

import java.text.Normalizer

/** The AI's answer to one form question: [value] null = not discussed; [lines] = the transcript lines it read. */
data class AiAnswer(val value: String?, val confidence: Float, val lines: IntRange?)

/**
 * On-device form filling with the fine-tuned Gemma 3 4B, the same decisions as imci_v5/hierarchy_fill.py
 * (--lang bn --gate-chunk 16 --item-chunk 8 --overlap 3), which is also the exact prompt format it was trained on
 * (imci_synth_v1/make_examples.py):
 *   - one question per call, on overlapping transcript windows: gate questions read 16-line windows, all others 8;
 *   - follow-ups are asked only when the form shows them (gate answered yes, etc.);
 *   - yes/no: the letter A / B / C is read from the next-token logits; below 60 % it stays not discussed;
 *   - choice: every option's JSON answer is scored, the most likely wins; number: greedy text;
 *   - the most confident window that gives an answer wins.
 * Questions are asked in rounds (gates first, then what they open), and inside a round window by window, so each
 * window's transcript is decoded once and only the short question part is decoded per question.
 */
class FormFiller(
    private val form: ImciForm,
    /** BanglaBERT ensemble for the yes/no questions (fast); null = Gemma answers everything */
    private val bert: BertClassifier?,
    /** loads Gemma on first use (it is only needed for choice/number questions when [bert] is set); 0 = unavailable */
    private val gemma: () -> Long,
    /** BanglaBERT probabilities per (question, window text): live filling re-walks the form after every new chunk
     *  and only the windows that changed are classified again */
    private val bertCache: MutableMap<String, FloatArray> = java.util.concurrent.ConcurrentHashMap(),
) {
    private val model: Long by lazy { gemma() }

    companion object {
        const val GATE_WINDOW = 16
        const val ITEM_WINDOW = 8
        const val OVERLAP = 3
        const val MIN_CONF = 0.6f
        private const val MAX_WORDS = 16          // CTC chunks have no sentence marks: split long ones like VAD phrases

        val SYSTEM = "আপনি একজন স্বাস্থ্যকর্মীর বাড়ি পরিদর্শনের একটি স্বয়ংক্রিয় বাংলা প্রতিলিপি পড়ছেন (বরিশালের আঞ্চলিক ভাষা, " +
            "শনাক্তকরণে ভুল আছে, যতিচিহ্ন নেই, কে বলছে তা লেখা নেই)। পরিদর্শনটি ২-৫৯ মাস বয়সী একটি শিশুকে নিয়ে। " +
            "এই শিশু সম্পর্কে একটি প্রশ্নের উত্তর দিন। উত্তর দেবেন কেবল তখনই যখন প্রতিলিপিতে তা স্পষ্ট আছে: মায়ের উত্তর, " +
            "অথবা স্বাস্থ্যকর্মী যা দেখেছেন বা মেপেছেন তা বলেছেন। বিষয়টি যদি শুধু বোঝানো বা শেখানো হয়, স্পষ্ট উত্তর ছাড়া " +
            "জিজ্ঞেস করা হয়, অথবা উল্লেখই না থাকে, তাহলে উত্তর 'আলোচনা হয়নি'। অ্যাপের রেকর্ড করা কণ্ঠ বিকল্পগুলো পড়ে " +
            "শোনাতে পারে ([options read out: yes / no] হিসেবে দেখানো); সেটি উত্তর নয় - উত্তর হলো তার পরের জবাব।"
        private const val YN = "A = হ্যাঁ, B = না, C = আলোচনা হয়নি। শুধু একটি অক্ষর দিয়ে উত্তর দিন।"
        private const val OPT = "নিচের বিকল্পগুলোর একটি দিয়ে উত্তর দিন: "
        private const val NUM = "প্রতিলিপিতে বলা সংখ্যা/মান দিয়ে উত্তর দিন, না থাকলে not_discussed।"
        private const val JSON_HINT = "JSON ফেরত দিন {\"answer\": ...}."
        private val OPTIONS_READ = Regex(nfc("(এক|একখ|এখ|দেখ|১|ওয়ান)\\s*(হ্যাঁ|হ্যা|হা)\\s*(দুই|তুই|তই|দই|টুই|২|টু)\\s*না"))

        fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

        /** Transcript chunks -> numbered lines, as the models saw them in training (one sentence per line). */
        fun lines(chunks: List<String>): List<String> {
            // only transcripts without sentence marks (CTC) get long chunks cut; punctuated ones stay whole sentences
            val cut = chunks.none { SENTENCE_END.containsMatchIn(it) }
            return chunks.flatMap { c ->
            nfc(c).trim().split(Regex("(?<=[।?!])\\s+")).filter { it.isNotBlank() }.flatMap { s ->
                val w = s.trim().split(Regex("\\s+"))
                if (!cut || w.size <= MAX_WORDS) listOf(s.trim())
                else {                                       // equal pieces of about 10 words
                    val pieces = (w.size + 9) / 10
                    w.chunked((w.size + pieces - 1) / pieces).map { it.joinToString(" ") }
                }
            }
            }.map { it.replace(OPTIONS_READ, "[options read out: yes / no]") }
        }
        private val SENTENCE_END = Regex("[।?!]")

        /** Measurement/choice questions: Gemma's job, or the health worker's when Gemma is off ("Please enter"). */
        val MEASURE_KEYS get() = GEMMA_WORDS.keys

        /** With BanglaBERT on, Gemma only reads windows that mention its (measurement) question. */
        private val GEMMA_WORDS = mapOf(
            "o40" to "শ্বাস|মিনিট|গুন",
            "o51" to "চামড়া|চামড়া|পেট|টেনে|চিমটি",
            "o52" to "আরডিটি|RDT|ম্যালেরিয়া|ম্যালেরিয়া",
            "o68" to "ওজন|উচ্চতা|লম্বা|জেড|স্কোর|Z",
            "o69" to "মুয়াক|মোয়াক|MUAC|ফিতা|ফিতা|টেপ|মিলিমিটার|সবুজ|হলুদ|লাল",
            "o73" to "তালু|ফ্যাকাসে|রক্ত",
            "q20" to "দিন|সপ্তাহ|হপ্তা",
        ).mapValues { Regex(nfc(it.value), RegexOption.IGNORE_CASE) }

        fun windows(n: Int, size: Int): List<IntRange> =
            if (size >= n) listOf(0 until n)
            else (0 until n - OVERLAP step maxOf(1, size - OVERLAP)).map { s -> s until minOf(s + size, n) }
    }

    private val letters: IntArray by lazy {
        intArrayOf(tok("A"), tok("B"), tok("C"))
    }

    private fun tok(s: String) = LlamaNative.tokenize(model, s.toByteArray()).last()

    private fun questionText(it: FormItem) = it.textBn.ifBlank { it.text }

    /** Walks the form; [onProgress] gets (questions answered, questions known so far, current question). */
    fun fill(transcriptLines: List<String>, onProgress: (Int, Int, String) -> Unit): Map<String, AiAnswer> {
        val lines = transcriptLines
        val answers = LinkedHashMap<String, AiAnswer>()
        val decided = HashSet<String>()                      // answered, or hidden by the skip rules
        val gates = form.sections.mapNotNull { it.gate }.toSet()
        val order = form.sections.flatMap { it.keys }.filter { it in form.items }
        var done = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) break
            // questions whose skip rule only depends on questions already decided
            val ready = order.filter { k -> k !in decided && referenced(form.showIf(k)).all { it in decided } }
            if (ready.isEmpty()) break
            val values = answers.mapNotNull { (k, a) -> a.value?.let { k to it } }.toMap()
            val ask = ready.filter { form.shown(it, values) }
            decided += ready - ask.toSet()                   // hidden by the form: never asked
            val total = done + ask.size + (order.size - decided.size - ask.size).coerceAtLeast(0) / 2
            for (size in listOf(GATE_WINDOW, ITEM_WINDOW)) {
                val keys = ask.filter { (it in gates) == (size == GATE_WINDOW) }
                if (keys.isEmpty()) continue
                val best = HashMap<String, AiAnswer>()
                val ws = windows(lines.size, size)
                val texts = ws.map { w -> w.joinToString("\n") { "L${it + 1}: ${lines[it]}" } }
                // yes/no questions: the BanglaBERT ensemble reads every window of a question in one batch
                val bertKeys = if (bert != null) keys.filter { form.items.getValue(it).isYesNo } else emptyList()
                for ((qi, k) in bertKeys.withIndex()) {
                    if (Thread.currentThread().isInterrupted) break
                    val q = questionText(form.items.getValue(k))
                    val todo = texts.filter { "$q\u0000$it" !in bertCache }
                    if (todo.isNotEmpty()) bert!!.classify(q, todo).forEachIndexed { i, p -> bertCache["$q\u0000${todo[i]}"] = p }
                    val probs = texts.map { bertCache.getValue("$q\u0000$it") }
                    for ((wi, p) in probs.withIndex()) {
                        val c = (0..2).maxBy { p[it] }
                        if (c < 2 && p[c] >= MIN_CONF && (best[k] == null || p[c] > best[k]!!.confidence))
                            best[k] = AiAnswer(if (c == 0) "yes" else "no", p[c], ws[wi])
                    }
                    onProgress(done + qi + 1, maxOf(total, done + qi + 1), form.items.getValue(k).text)
                }
                // everything else: Gemma, window by window (with BanglaBERT on: only windows that mention the question)
                val gemmaKeys = keys - bertKeys.toSet()
                val need = ws.indices.filter { wi ->
                    gemmaKeys.any { k -> bert == null || GEMMA_WORDS[k]?.containsMatchIn(texts[wi]) == true }
                }
                for ((n, wi) in need.withIndex()) {
                    if (Thread.currentThread().isInterrupted || model == 0L) break
                    val w = ws[wi]
                    val prefix = "<start_of_turn>user\n$SYSTEM\n\nপ্রতিলিপি:\n" + texts[wi]
                    if (LlamaNative.setPrefix(model, prefix.toByteArray()) < 0) continue
                    for (k in gemmaKeys) {
                        if (bert != null && GEMMA_WORDS[k]?.containsMatchIn(texts[wi]) != true) continue
                        val a = askOne(form.items.getValue(k)) ?: continue
                        val cur = best[k]
                        if (a.value != null && (cur?.value == null || a.confidence > cur.confidence)) best[k] = a.copy(lines = w)
                    }
                    // progress after every window, so long rounds never look stuck
                    val part = done + bertKeys.size + gemmaKeys.size * (n + 1) / need.size
                    onProgress(part, maxOf(total, part), "AI reading transcript part ${n + 1}/${need.size}")
                }
                for (k in keys) {
                    answers[k] = best[k] ?: AiAnswer(null, 0f, null)
                    decided += k
                    done++
                }
                onProgress(done, maxOf(total, done), "round done")
            }
        }
        return answers
    }

    private fun referenced(c: org.json.JSONObject?): Set<String> {
        if (c == null) return emptySet()
        if (c.has("any")) {
            val a = c.getJSONArray("any")
            return (0 until a.length()).flatMap { referenced(a.getJSONObject(it)) }.toSet()
        }
        return c.keys().asSequence().toSet()
    }

    /** One question against the cached window prompt. */
    private fun askOne(it: FormItem): AiAnswer? {
        val q = "\n\nএই শিশু সম্পর্কে প্রশ্ন: ${questionText(it)}\n\n"
        val end = "<end_of_turn>\n<start_of_turn>model\n"
        if (it.isYesNo) {
            val lg = LlamaNative.nextLogits(model, (q + YN + end).toByteArray(), letters)
            if (lg.any { v -> v.isNaN() }) return null
            val mx = lg.max()
            val p = lg.map { v -> kotlin.math.exp((v - mx).toDouble()) }
            val z = p.sum()
            val probs = p.map { v -> (v / z).toFloat() }
            val i = probs.indices.maxBy { j -> probs[j] }
            val value = if (probs[i] < MIN_CONF || i == 2) null else if (i == 0) "yes" else "no"
            return AiAnswer(value, probs[i], null)
        }
        if (it.options.isNotEmpty()) {
            val how = OPT + it.options.joinToString(" | ") + " | not_discussed."
            if (LlamaNative.evalSuffix(model, (q + how + " " + JSON_HINT + end).toByteArray()) < 0) return null
            val choices = it.options + "not_discussed"
            val lp = choices.map { o -> LlamaNative.contLogprob(model, "{\"answer\": \"$o\"}".toByteArray()) }
            val mx = lp.max()
            val z = lp.sumOf { v -> kotlin.math.exp((v - mx).toDouble()) }
            val i = lp.indices.maxBy { j -> lp[j] }
            val conf = (1.0 / z).toFloat()                    // probability share of the best option
            return AiAnswer(if (i == it.options.size) null else choices[i], conf, null)
        }
        if (it.answerType == "number") {
            if (LlamaNative.evalSuffix(model, (q + NUM + " " + JSON_HINT + end).toByteArray()) < 0) return null
            val out = String(LlamaNative.generate(model, 16, '}'.code.toByte()))
            val v = Regex("\"answer\"\\s*:\\s*\"?([^\"}]*)").find(out)?.groupValues?.get(1)?.trim()
            val num = v?.let { s -> Regex("\\d+").find(s.map { ch -> if (ch in '০'..'৯') '0' + (ch - '০') else ch }.joinToString(""))?.value }
            return AiAnswer(num, if (num != null) 1f else 0f, null)
        }
        return null                                           // free text: left to the health worker
    }
}
