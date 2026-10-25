package org.imci.transcriber

/** JNI bindings to llama.cpp - see app/src/main/cpp/imci_llm.cpp. Text is passed as UTF-8 bytes. */
object LlamaNative {
    init {
        System.loadLibrary("imci_jni")
    }

    /** Loads a GGUF model; 0 on failure. ggml backends must be loaded first (WhisperNative.loadBackends). */
    external fun load(path: String, nThreads: Int, nCtx: Int): Long
    external fun free(h: Long)
    /** true stops the decode in progress (and every later one until set back to false). */
    external fun cancel(on: Boolean)
    external fun tokenize(h: Long, text: ByteArray): IntArray

    /** Replaces the cached prompt (BOS added, special tokens parsed); returns its token count or -1. */
    external fun setPrefix(h: Long, text: ByteArray): Int
    /** Decodes [suffix] after the prefix; next-token logits of [candidates] (NaN on error). */
    external fun nextLogits(h: Long, suffix: ByteArray, candidates: IntArray): FloatArray
    /** Decodes [suffix] after the prefix and keeps it for [contLogprob] / [generate]; tokens or -1. */
    external fun evalSuffix(h: Long, suffix: ByteArray): Int
    /** Sum of log-probabilities of [cont] after prefix + suffix. */
    external fun contLogprob(h: Long, cont: ByteArray): Float
    /** Greedy text after prefix + suffix, up to [maxTokens] or until the byte [stop] appears. */
    external fun generate(h: Long, maxTokens: Int, stop: Byte): ByteArray
}
