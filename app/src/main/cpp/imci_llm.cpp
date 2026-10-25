// JNI bridge to llama.cpp for on-device IMCI form filling (fine-tuned Gemma 3 4B, q4_k_m GGUF).
//
// Same decisions as imci_v5/hierarchy_fill.py, but read straight from the logits instead of sampled text:
//   setPrefix(window)  - the system prompt + transcript window is decoded once and kept in the KV cache;
//   nextLogits(suffix) - per question only the question part is decoded on top of that prefix
//                        (the cache is cut back to the prefix first), and the logits of the next token
//                        are returned for the candidate tokens (the letters A / B / C);
//   evalSuffix + contLogprob - for choice items: log-probability of each option's JSON continuation;
//   generate           - greedy text after the current state (number items).
// Text crosses JNI as UTF-8 bytes (Bangla is multi-byte; NewStringUTF aborts on split characters).

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "imci_llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_cancel{false};

struct Llm {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_prefix = 0;   // tokens of the cached window prompt
    int n_suffix = 0;   // tokens of the question part on top of it (evalSuffix)
    llama_token last_suffix = -1;   // its last token: re-decoded to score each choice option from the same point
};

std::string bytes(JNIEnv * env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string s(n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

std::vector<llama_token> tokenize(const Llm * l, const std::string & text, bool add_special) {
    std::vector<llama_token> t(text.size() + 16);
    int n = llama_tokenize(l->vocab, text.data(), (int32_t) text.size(), t.data(), (int32_t) t.size(), add_special, true);
    if (n < 0) { t.resize(-n); n = llama_tokenize(l->vocab, text.data(), (int32_t) text.size(), t.data(), (int32_t) t.size(), add_special, true); }
    t.resize(n > 0 ? n : 0);
    return t;
}

// decodes tokens at positions pos.., logits only for the last one (or for all if all_logits)
bool decode(Llm * l, const std::vector<llama_token> & toks, int pos, bool all_logits) {
    const int n_batch = (int) llama_n_batch(l->ctx);
    for (size_t i = 0; i < toks.size(); i += n_batch) {
        if (g_cancel.load()) return false;
        int n = (int) std::min(toks.size() - i, (size_t) n_batch);
        llama_batch b = llama_batch_init(n, 0, 1);
        for (int j = 0; j < n; j++) {
            b.token[j] = toks[i + j]; b.pos[j] = pos + (int) i + j; b.n_seq_id[j] = 1; b.seq_id[j][0] = 0;
            b.logits[j] = all_logits || (i + j == toks.size() - 1);
        }
        b.n_tokens = n;
        int rc = llama_decode(l->ctx, b);
        llama_batch_free(b);
        if (rc != 0) { LOGW("llama_decode failed: %d", rc); return false; }
    }
    return true;
}

void cut(Llm * l, int keep) { llama_memory_seq_rm(llama_get_memory(l->ctx), 0, keep, -1); }

float log_softmax_at(const float * logits, int n_vocab, llama_token tok) {
    float mx = logits[0];
    for (int i = 1; i < n_vocab; i++) mx = std::max(mx, logits[i]);
    double s = 0;
    for (int i = 0; i < n_vocab; i++) s += std::exp((double) logits[i] - mx);
    return (float) (logits[tok] - mx - std::log(s));
}

bool abort_cb(void *) { return g_cancel.load(); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_imci_transcriber_LlamaNative_load(JNIEnv * env, jobject, jstring path, jint n_threads, jint n_ctx) {
    static bool inited = false;
    if (!inited) { llama_backend_init(); inited = true; }
    const char * p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) { LOGW("could not load model"); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx; cp.n_batch = n_ctx; cp.n_ubatch = 512;
    cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    cp.abort_callback = abort_cb; cp.abort_callback_data = nullptr;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOGW("could not create context"); return 0; }
    auto * l = new Llm{model, ctx, llama_model_get_vocab(model)};
    LOGI("model loaded: ctx %d, threads %d", n_ctx, n_threads);
    return (jlong) l;
}

JNIEXPORT void JNICALL
Java_org_imci_transcriber_LlamaNative_free(JNIEnv *, jobject, jlong h) {
    auto * l = (Llm *) h;
    if (!l) return;
    llama_free(l->ctx); llama_model_free(l->model); delete l;
}

JNIEXPORT void JNICALL
Java_org_imci_transcriber_LlamaNative_cancel(JNIEnv *, jobject, jboolean on) { g_cancel.store(on); }

JNIEXPORT jintArray JNICALL
Java_org_imci_transcriber_LlamaNative_tokenize(JNIEnv * env, jobject, jlong h, jbyteArray text) {
    auto t = tokenize((Llm *) h, bytes(env, text), false);
    jintArray out = env->NewIntArray((jsize) t.size());
    env->SetIntArrayRegion(out, 0, (jsize) t.size(), t.data());
    return out;
}

// Replaces the cached prompt with [text] (BOS + special tokens parsed). Returns its token count, -1 on error.
JNIEXPORT jint JNICALL
Java_org_imci_transcriber_LlamaNative_setPrefix(JNIEnv * env, jobject, jlong h, jbyteArray text) {
    auto * l = (Llm *) h;
    llama_memory_clear(llama_get_memory(l->ctx), true);
    auto t = tokenize(l, bytes(env, text), true);
    l->n_prefix = 0; l->n_suffix = 0;
    if ((int) t.size() >= (int) llama_n_ctx(l->ctx) - 256 || !decode(l, t, 0, false)) return -1;
    l->n_prefix = (int) t.size();
    return l->n_prefix;
}

// Decodes [suffix] on top of the prefix and returns the next-token logits of [candidates] (NaN on error).
JNIEXPORT jfloatArray JNICALL
Java_org_imci_transcriber_LlamaNative_nextLogits(JNIEnv * env, jobject, jlong h, jbyteArray suffix, jintArray candidates) {
    auto * l = (Llm *) h;
    cut(l, l->n_prefix);
    auto t = tokenize(l, bytes(env, suffix), false);
    jsize n = env->GetArrayLength(candidates);
    std::vector<jint> c(n); env->GetIntArrayRegion(candidates, 0, n, c.data());
    std::vector<float> out(n, NAN);
    if (decode(l, t, l->n_prefix, false)) {
        const float * lg = llama_get_logits_ith(l->ctx, -1);
        for (int i = 0; i < n; i++) out[i] = lg[c[i]];
        l->n_suffix = (int) t.size();
    }
    jfloatArray a = env->NewFloatArray(n);
    env->SetFloatArrayRegion(a, 0, n, out.data());
    return a;
}

// Decodes [suffix] on top of the prefix and keeps it (for contLogprob / generate). Returns tokens, -1 on error.
JNIEXPORT jint JNICALL
Java_org_imci_transcriber_LlamaNative_evalSuffix(JNIEnv * env, jobject, jlong h, jbyteArray suffix) {
    auto * l = (Llm *) h;
    cut(l, l->n_prefix);
    auto t = tokenize(l, bytes(env, suffix), false);
    if (t.empty() || !decode(l, t, l->n_prefix, false)) return -1;
    l->n_suffix = (int) t.size();
    l->last_suffix = t.back();
    return l->n_suffix;
}

// Sum of log-probabilities of [cont] following prefix + suffix (the cache is cut back after each call).
JNIEXPORT jfloat JNICALL
Java_org_imci_transcriber_LlamaNative_contLogprob(JNIEnv * env, jobject, jlong h, jbyteArray cont) {
    auto * l = (Llm *) h;
    const int base = l->n_prefix + l->n_suffix;
    auto t = tokenize(l, bytes(env, cont), false);
    if (t.empty()) return 0.f;
    if (t.empty() || l->last_suffix < 0) return t.empty() ? 0.f : -INFINITY;
    const int nv = llama_vocab_n_tokens(l->vocab);
    // re-decode the last suffix token followed by cont[0..n-2]: logits row i then predicts cont[i]
    cut(l, base - 1);
    std::vector<llama_token> seq{l->last_suffix};
    seq.insert(seq.end(), t.begin(), t.end() - 1);
    if (!decode(l, seq, base - 1, true)) return -INFINITY;
    float total = 0.f;
    for (size_t i = 0; i < t.size(); i++) total += log_softmax_at(llama_get_logits_ith(l->ctx, (int) i), nv, t[i]);
    return total;
}

// Greedy continuation after prefix + suffix (call evalSuffix first); stops at an end token or a byte in [stop].
JNIEXPORT jbyteArray JNICALL
Java_org_imci_transcriber_LlamaNative_generate(JNIEnv * env, jobject, jlong h, jint max_tokens, jbyte stop) {
    auto * l = (Llm *) h;
    int pos = l->n_prefix + l->n_suffix;
    const int nv = llama_vocab_n_tokens(l->vocab);
    std::string out;
    // fresh logits for the position after the suffix (contLogprob may have decoded past it)
    cut(l, pos - 1);
    if (l->last_suffix < 0 || !decode(l, {l->last_suffix}, pos - 1, false)) return env->NewByteArray(0);
    const float * lg = llama_get_logits_ith(l->ctx, -1);
    for (int k = 0; k < max_tokens && !g_cancel.load(); k++) {
        int best = 0; for (int i = 1; i < nv; i++) if (lg[i] > lg[best]) best = i;
        if (llama_vocab_is_eog(l->vocab, best)) break;
        char buf[128]; int n = llama_token_to_piece(l->vocab, best, buf, sizeof buf, 0, true);
        if (n > 0) out.append(buf, n);
        if (stop && out.find((char) stop) != std::string::npos) break;
        if (!decode(l, {best}, pos++, false)) break;
        lg = llama_get_logits_ith(l->ctx, -1);
    }
    jbyteArray a = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(a, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return a;
}

}  // extern "C"
