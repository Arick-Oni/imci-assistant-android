package org.imci.transcriber

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Status {
    data object Idle : Status
    data object Preparing : Status
    data class Recording(val seconds: Float, val live: Boolean = false, val waiting: Int = 0, val paused: Boolean = false) : Status
    data class Finishing(val waiting: Int) : Status
    data class Decoding(val progress: Float) : Status
    data class Transcribing(val done: Int, val total: Int, val elapsed: Float, val audioSeconds: Float) : Status
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val transcriber = Transcriber(app)
    private val store = TranscriptStore(app)
    private val recorder = Recorder()
    private val threads = transcriber.defaultThreads()
    private var job: Job? = null
    private var live: LiveSession? = null
    private var liveJobs: Pair<Job, Job>? = null
    @Volatile private var liveWaiting = 0
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    var status by mutableStateOf<Status>(Status.Idle); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var liveTitle by mutableStateOf("")
    val liveChunks = mutableStateListOf<Chunk>()
    var lastResult by mutableStateOf<Transcript?>(null); private set
    val history = mutableStateListOf<Transcript>()
    /** Live mode: transcribe while recording (remembered between launches). */
    var liveMode by mutableStateOf(prefs.getBoolean("live", true))
        private set

    fun setLive(on: Boolean) {
        liveMode = on
        prefs.edit().putBoolean("live", on).apply()
    }

    val busy get() = status !is Status.Idle
    /** Pause / resume a recording: paused audio is not kept, so it is neither transcribed nor in the saved file. */
    fun togglePause() { if (recorder.isRecording) recorder.pause(!recorder.isPaused) }

    /** Microphone loudness 0..1 while recording (read every frame by the voice animation). */
    val micLevel get() = recorder.level

    /** Speech models to choose from (built-in + .bin files in the models folder); the choice is remembered. */
    val models = mutableStateListOf<SpeechModel>()
    var modelId by mutableStateOf(prefs.getString("model", Transcriber.BUILTIN) ?: Transcriber.BUILTIN)
        private set
    val modelsDir get() = transcriber.modelsDir

    // ---------------------------------------------------------------- IMCI form (fine-tuned Gemma on the phone)
    val form = ImciForm(app)
    private val forms = VisitFormStore(app)
    val visitForms = androidx.compose.runtime.mutableStateMapOf<String, VisitForm>()
    /** Form-filling models: .gguf files in Android/data/org.imci.transcriber/files/models/llm/. */
    val llmDir: File = File(transcriber.modelsDir, "llm").apply { mkdirs() }
    val llmModels = mutableStateListOf<File>()
    var llmPath by mutableStateOf(prefs.getString("llm", null)); private set
    var autoFill by mutableStateOf(prefs.getBoolean("auto_fill", true)); private set
    var fill by mutableStateOf<FillProgress?>(null); private set
    private var llm = 0L
    private var llmLoadedPath: String? = null
    private var fillJob: Job? = null

    data class FillProgress(val visitId: String, val done: Int, val total: Int, val question: String,
                            val seconds: Float, val loading: Boolean = false)

    fun refreshLlmModels() {
        llmModels.clear()
        llmModels.addAll(llmDir.listFiles { f -> f.extension == "gguf" && !f.name.contains("mmproj") }.orEmpty().sortedBy { it.name })
        if (llmPath == null || llmModels.none { it.absolutePath == llmPath }) llmPath = llmModels.firstOrNull()?.absolutePath
    }

    fun selectLlm(f: File) { llmPath = f.absolutePath; prefs.edit().putString("llm", llmPath).apply() }
    fun changeAutoFill(on: Boolean) { autoFill = on; prefs.edit().putBoolean("auto_fill", on).apply() }

    /** Gemma for the measurement/choice questions: off by default - the health worker enters them ("Please enter"). */
    var useGemma by mutableStateOf(prefs.getBoolean("use_gemma", false)); private set
    fun changeUseGemma(on: Boolean) { useGemma = on; prefs.edit().putBoolean("use_gemma", on).apply() }

    /** Copies a .gguf picked by the user into the form-model folder (a 2.5 GB copy takes a minute or two). */
    fun importLlm(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            status = Status.Preparing
            val name = displayName(uri).let { if (it.endsWith(".gguf")) it else "$it.gguf" }
            runCatching {
                withContext(Dispatchers.IO) {
                    val tmp = File(llmDir, "$name.part")
                    app.contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                    check(tmp.renameTo(File(llmDir, name))) { "could not save $name" }
                }
            }.onSuccess { refreshLlmModels(); selectLlm(File(llmDir, name)) }
             .onFailure { error = "Could not add the form model: ${it.message}" }
            status = Status.Idle
        }
    }

    fun formOf(id: String): VisitForm = visitForms[id] ?: (forms.load(id) ?: VisitForm()).also { visitForms[id] = it }

    /** BanglaBERT ensemble (bb*.onnx + tokenizer/) in Android/data/org.imci.transcriber/files/models/bert/. */
    val bertDir: File = File(transcriber.modelsDir, "bert").apply { mkdirs() }
    val bertCount get() = bertDir.listFiles { f -> f.name.startsWith("bb") && f.extension == "onnx" }?.size ?: 0
    private var bert: BertClassifier? = null
    /** BanglaBERT results per (question, window); emptied when a new recording starts */
    private val bertCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    private val gemmaPath get() = if (useGemma) llmPath else null

    /** Something can fill forms: the BanglaBERT ensemble and/or (when switched on) a GGUF model. */
    val canFill get() = bertCount > 0 || gemmaPath != null

    /** BanglaBERT file count, refreshed after an import (the Settings line and canFill read it). */
    var bertFiles by mutableStateOf(bertCount); private set

    /**
     * Copies BanglaBERT files picked by the user (Android/data is closed to file managers): bb*.onnx and
     * tokenizer_cases.json into models/bert/, vocab.txt and tokenizer_config.json into models/bert/tokenizer/.
     */
    fun importBert(uris: List<Uri>) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            status = Status.Preparing
            runCatching {
                withContext(Dispatchers.IO) {
                    val skipped = mutableListOf<String>()
                    for (uri in uris) {
                        val name = displayName(uri)
                        val dir = when {
                            name.startsWith("bb") && name.endsWith(".onnx") || name == "tokenizer_cases.json" -> bertDir
                            name == "vocab.txt" || name == "tokenizer_config.json" -> File(bertDir, "tokenizer").apply { mkdirs() }
                            else -> { skipped += name; continue }
                        }
                        val tmp = File(dir, "$name.part")
                        app.contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                        File(dir, name).delete()
                        check(tmp.renameTo(File(dir, name))) { "could not save $name" }
                    }
                    skipped
                }
            }.onSuccess { skipped ->
                bert?.close(); bert = null; bertCache.clear()        // reload with the new files on the next fill
                bertFiles = bertCount
                if (skipped.isNotEmpty()) error = "Not BanglaBERT files, skipped: ${skipped.joinToString()}"
            }.onFailure { error = "Could not add the BanglaBERT files: ${it.message}" }
            status = Status.Idle
        }
    }

    /** Loads the BanglaBERT ensemble once (a few seconds) and checks the Kotlin tokenizer against Python's. */
    private fun ensureBert(): BertClassifier? {
        if (bert == null && bertCount > 0) {
            bert = BertClassifier(bertDir, threads)
            val bad = bert!!.selfTest(bertDir)
            android.util.Log.i("imci_bert", "BanglaBERT x${bert!!.size} loaded, tokenizer check: $bad case(s) differ")
            check(bad <= 0) { "BanglaBERT tokenizer does not match the trained one ($bad cases differ)" }
        }
        return bert
    }

    private fun modelLabel(path: String?) =
        listOfNotNull(bert?.let { "BanglaBERT x${it.size}" }, path?.let { File(it).nameWithoutExtension }).joinToString(" + ")

    /** Puts the AI answers into visit [id]'s form; answers the worker set or cleared stay as the worker left them. */
    private fun mergeAi(id: String, lines: List<String>, ai: Map<String, AiAnswer>, model: String, seconds: Float) {
        val old = formOf(id)
        val values = HashMap<String, String>()
        val sources = HashMap<String, String>()
        ai.forEach { (k, a) -> a.value?.let { values[k] = it; sources[k] = "ai" } }
        old.sources.filterValues { it == "worker" }.keys.forEach { k ->
            old.values[k]?.let { values[k] = it } ?: values.remove(k); sources[k] = "worker"
        }
        saveForm(id, old.copy(ai = ai, aiModel = model, aiSeconds = seconds, lines = lines, values = values, sources = sources))
    }

    // ---------------------------------------------------------------- live form: BanglaBERT while recording
    /** Visit id of the recording in progress (its form fills while the visit goes on); null when not recording live. */
    var liveId by mutableStateOf<String?>(null); private set
    /** Newest AI answers of the live visit, newest first (key -> value), for the "just added" strip. */
    val liveRecent = mutableStateListOf<Pair<String, String>>()
    private var liveFillJob: Job? = null

    /** Re-walks the form whenever new transcript lines arrive; only new windows go through BanglaBERT. */
    private fun startLiveFill(id: String) {
        liveFillJob = viewModelScope.launch(Dispatchers.Default) {
            val b = runCatching { ensureBert() }.onFailure { viewModelScope.launch { error = it.message } }.getOrNull() ?: return@launch
            val filler = FormFiller(form, b, { 0L }, bertCache)
            val t0 = SystemClock.elapsedRealtime()
            var seen = -1
            while (isActive) {
                val texts = withContext(Dispatchers.Main) { liveChunks.sortedBy { it.start }.map { it.text } }
                val lines = FormFiller.lines(texts)
                if (lines.size != seen && lines.isNotEmpty()) {
                    seen = lines.size
                    val p0 = SystemClock.elapsedRealtime()
                    val ai = filler.fill(lines) { _, _, _ -> }
                    tlog("form  live pass over %d lines took %.1f s, %d answered".format(lines.size,
                        (SystemClock.elapsedRealtime() - p0) / 1000f, ai.count { it.value.value != null }))
                    withContext(Dispatchers.Main) {
                        if (liveId != id) return@withContext
                        val before = formOf(id).ai
                        mergeAi(id, lines, ai, modelLabel(null) + " (live)", (SystemClock.elapsedRealtime() - t0) / 1000f)
                        ai.filter { (k, a) -> a.value != null && before[k]?.value != a.value }.forEach { (k, a) ->
                            liveRecent.removeAll { it.first == k }; liveRecent.add(0, k to a.value!!)
                        }
                        while (liveRecent.size > 4) liveRecent.removeAt(liveRecent.lastIndex)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun loadGemma(path: String?): Long {
        if (path == null) return 0L
        if (llm == 0L || llmLoadedPath != path) {
            if (llm != 0L) LlamaNative.free(llm)
            llm = 0L
            transcriber.ensureBackends()
            llm = LlamaNative.load(path, threads, 4096)
            if (llm != 0L) llmLoadedPath = path
        }
        return llm
    }

    /**
     * Fills the IMCI form of transcript [t] on the phone: the yes/no questions with the BanglaBERT ensemble (seconds),
     * the choice/number questions with the Gemma GGUF (loaded only when a window mentions them). Without BanglaBERT
     * models Gemma answers everything (slow on a phone CPU).
     */
    fun fillForm(t: Transcript) {
        if (fill != null) return
        if (!canFill) { error = "Add the form-filling models in Settings first"; return }
        val path = gemmaPath
        fillJob = viewModelScope.launch {
            liveFillJob?.let { it.cancel(); it.join() }      // the live pass of this visit hands over (its windows are cached)
            val t0 = SystemClock.elapsedRealtime()
            val secs = { (SystemClock.elapsedRealtime() - t0) / 1000f }
            fill = FillProgress(t.id, 0, 0, "Loading the form model…", 0f, loading = true)
            runCatching {
                withContext(Dispatchers.Default) {
                    ensureBert()
                    LlamaNative.cancel(false)
                    val lines = FormFiller.lines(t.chunks.map { it.text })
                    val s0 = secs()
                    viewModelScope.launch { fill = FillProgress(t.id, 0, 0, "reading the transcript…", s0) }
                    val ai = FormFiller(form, bert, {
                        viewModelScope.launch { fill = fill?.copy(question = "loading Gemma for the measurement questions…") }
                        loadGemma(path)
                    }, bertCache).fill(lines) { done, total, q ->
                        val s = secs()
                        viewModelScope.launch { if (fill?.visitId == t.id) fill = FillProgress(t.id, done, total, q, s) }
                    }
                    lines to ai
                }
            }.onSuccess { (lines, ai) ->
                mergeAi(t.id, lines, ai, modelLabel(path), secs())
                tlog("FORM READY: final pass %.1f s, %d answered %s".format(secs(), ai.count { it.value.value != null },
                    ai.filterValues { it.value != null }.mapValues { it.value.value }))
                testLog?.close(); testLog = null
            }.onFailure { if (fill != null) error = "Form filling failed: ${it.message}" }
            fill = null
        }
    }

    fun cancelFill() {
        LlamaNative.cancel(true)
        fillJob?.cancel()
        fill = null
    }

    /** The worker sets (or clears, [value] null) an answer; follow-ups the form no longer shows are cleared too. */
    fun setAnswer(id: String, key: String, value: String?) {
        val f = formOf(id)
        val values = f.values.toMutableMap()
        val sources = f.sources.toMutableMap()
        if (value == null || values[key] == value) values.remove(key) else values[key] = value
        sources[key] = "worker"
        var changed = true
        while (changed) {                       // clear hidden follow-ups (their own follow-ups next round)
            changed = false
            for (k in values.keys.toList()) if (!form.shown(k, values)) { values.remove(k); sources[k] = "worker"; changed = true }
        }
        saveForm(id, f.copy(values = values, sources = sources, edits = f.edits + 1, reviewedMs = null))
    }

    fun confirmForm(id: String) { val f = formOf(id); saveForm(id, f.copy(reviewedMs = System.currentTimeMillis())) }

    fun formFile(id: String) = forms.file(id)

    private fun saveForm(id: String, f: VisitForm) {
        visitForms[id] = f
        viewModelScope.launch(Dispatchers.IO) { forms.save(id, f) }
    }

    init {
        refreshHistory()
        viewModelScope.launch {
            installBundledModels()
            refreshModels()
            refreshLlmModels()
            bertFiles = bertCount
            prepare()
        }
    }

    /**
     * Shareable APKs carry the speech and form models under assets/models/: copied once to the models folder
     * (where the engines load them from files), skipped when a file of the same size is already there.
     */
    private suspend fun installBundledModels() {
        val am = getApplication<Application>().assets
        fun walk(dir: String): List<String> = am.list(dir).orEmpty().flatMap { n ->
            val p = "$dir/$n"; if (am.list(p).isNullOrEmpty()) listOf(p) else walk(p)
        }
        val files = runCatching { walk("models") }.getOrDefault(emptyList())
        val todo = files.filter { a ->
            val dst = File(transcriber.modelsDir, a.removePrefix("models/"))
            val len = runCatching { am.openFd(a).use { it.length } }.getOrDefault(-1L)
            !dst.exists() || (len >= 0 && dst.length() != len)
        }
        if (todo.isEmpty()) return
        status = Status.Preparing
        runCatching {
            withContext(Dispatchers.IO) {
                for (a in todo) {
                    val dst = File(transcriber.modelsDir, a.removePrefix("models/")).apply { parentFile?.mkdirs() }
                    val tmp = File(dst.path + ".part")
                    am.open(a).use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                    dst.delete(); check(tmp.renameTo(dst)) { "could not install ${dst.name}" }
                }
            }
        }.onFailure { error = "Could not set up the built-in models: ${it.message}" }
        status = Status.Idle
    }

    private fun refreshModels() {
        models.clear()
        models.addAll(transcriber.availableModels())
    }

    /** Loads the chosen model (a few seconds; longer for big models). Falls back to the built-in one. */
    private fun prepare() {
        viewModelScope.launch {
            status = Status.Preparing
            ready = false
            val m = models.firstOrNull { it.id == modelId } ?: models.firstOrNull { it.id == Transcriber.BUILTIN }
                ?: models.firstOrNull { it.file?.parentFile?.name == "srotoswini" } ?: models.firstOrNull()
                ?: run { error = "No speech model: copy one to Android/data/org.imci.transcriber/files/models/"; status = Status.Idle; return@launch }
            runCatching { withContext(Dispatchers.Default) { transcriber.load(m) } }
                .onSuccess { ready = true; modelId = m.id }
                .onFailure {
                    error = it.message
                    if (m.id != Transcriber.BUILTIN && models.any { it.id == Transcriber.BUILTIN }) {
                        modelId = Transcriber.BUILTIN; status = Status.Idle; prepare(); return@launch
                    }
                }
            status = Status.Idle
        }
    }

    fun selectModel(id: String) {
        if (busy || id == modelId) return
        modelId = id
        prefs.edit().putString("model", id).apply()
        prepare()
    }

    /** Copies a whisper.cpp .bin picked by the user into the models folder and switches to it. */
    fun importModel(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            status = Status.Preparing
            val name = displayName(uri).let { if (it.endsWith(".bin")) it else "$it.bin" }
            runCatching {
                withContext(Dispatchers.IO) {
                    val tmp = File(modelsDir, "$name.part")
                    app.contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                    check(tmp.renameTo(File(modelsDir, name))) { "could not save $name" }
                }
            }.onSuccess {
                status = Status.Idle
                refreshModels()
                selectModel(name)
            }.onFailure {
                error = "Could not add the model: ${it.message}"
                status = Status.Idle
            }
        }
    }

    private val modelName get() = transcriber.model?.let {
        when {
            it.file == null -> "regional medium (built-in)"
            it.isCtc -> "${it.file.parentFile?.name} ${it.file.nameWithoutExtension}"
            else -> it.file.nameWithoutExtension
        }
    }

    fun transcribeFile(uri: Uri) {
        val app = getApplication<Application>()
        val title = displayName(uri)
        job = viewModelScope.launch {
            runCatching {
                status = Status.Decoding(0f)
                val samples = withContext(Dispatchers.Default) {
                    AudioDecoder.decode(app, uri) { status = Status.Decoding(it) }
                }
                run(title, samples)
            }.onFailure { if (isActive) error = "Could not transcribe: ${it.message}" }
            status = Status.Idle
        }
    }

    fun toggleRecording() {
        if (recorder.isRecording) {
            val id = store.newId()
            val wav = File(store.recordingsDir, "recording_$id.wav")
            val session = live
            job = viewModelScope.launch {
                if (session != null) finishLive(session, wav)
                else {
                    val samples = withContext(Dispatchers.IO) { recorder.stop(wav) }
                    runCatching { run(wav.name, samples) }.onFailure { error = "Could not transcribe: ${it.message}" }
                }
                status = Status.Idle
            }
        } else startRecording(null)
    }

    // ---------------------------------------------------------------- live test: a recording played as if heard live
    /** Timing log of a replayed visit (files/live_test.log): when text and answers appear, measured from the start. */
    private var testLog: java.io.PrintWriter? = null
    private var testT0 = 0L
    private fun tlog(msg: String) {
        val w = testLog ?: return
        val s = "%7.1f s  %s".format((SystemClock.elapsedRealtime() - testT0) / 1000f, msg)
        synchronized(w) { w.println(s); w.flush() }
        android.util.Log.i("imci_live_test", s)
    }

    /** Plays [file] into the live pipeline at real-time speed and stops by itself when the audio ends. */
    fun replayAsLive(file: File) {
        viewModelScope.launch {
            while (!ready || busy || fill != null) delay(500)
            val samples = withContext(Dispatchers.Default) { AudioDecoder.decode(getApplication(), Uri.fromFile(file)) }
            if (!liveMode) setLive(true)
            testLog?.close()
            testLog = java.io.PrintWriter(File(getApplication<Application>().getExternalFilesDir(null), "live_test.log"))
            testT0 = SystemClock.elapsedRealtime()
            tlog("replay ${file.name}: ${"%.1f".format(samples.size / AudioDecoder.SAMPLE_RATE.toFloat())} s audio, " +
                "model $modelName, BanglaBERT x$bertCount, threads $threads")
            startRecording(samples)
        }
    }

    private fun startRecording(replay: FloatArray?) {
            val useLive = liveMode && transcriber.isLoaded
            if (useLive) {
                liveTitle = "Live recording"
                liveChunks.clear()
                lastResult = null
                transcriber.resetCancel()
                liveWaiting = 0
                val session = LiveSession(transcriber, recorder, threads,
                    onChunk = { c ->
                        tlog("text  audio %.1f-%.1f s ready (%.1f s behind speech): %d chars".format(c.start, c.end,
                            (SystemClock.elapsedRealtime() - testT0) / 1000f - c.end, c.text.length))
                        viewModelScope.launch { liveChunks.add(c) }
                    },
                    onProgress = { n -> liveWaiting = n })
                live = session
                if (replay != null) recorder.startReplay(replay) {
                    tlog("audio ended")
                    viewModelScope.launch { if (recorder.isRecording) toggleRecording() }
                } else recorder.start()
                liveJobs = session.launch(viewModelScope)
                if (autoFill && bertCount > 0) {                 // the form fills while the visit goes on
                    val id = store.newId()
                    bertCache.clear(); liveRecent.clear(); visitForms.remove(id)
                    liveId = id
                    startLiveFill(id)
                }
            } else if (replay != null) recorder.startReplay(replay) {
                viewModelScope.launch { if (recorder.isRecording) toggleRecording() }
            } else {
                recorder.start()
            }
            job = viewModelScope.launch {
                while (recorder.isRecording) {
                    status = Status.Recording(recorder.seconds, useLive, liveWaiting, recorder.isPaused)
                    delay(250)
                }
            }
    }

    /** Stop pressed in live mode: send the last part, wait for the queue, save. */
    private suspend fun finishLive(session: LiveSession, wav: File) {
        val audioSeconds = withContext(Dispatchers.IO) { recorder.stop(wav) }.size / AudioDecoder.SAMPLE_RATE.toFloat()
        val t0 = SystemClock.elapsedRealtime()
        session.finish()
        val waiter = viewModelScope.launch {
            while (true) { status = Status.Finishing(liveWaiting); delay(250) }
        }
        liveJobs?.let { (chunker, worker) -> chunker.join(); worker.join() }
        waiter.cancel()
        live = null
        liveJobs = null
        val id = liveId ?: store.newId()
        if (transcriber.cancelled) {
            liveFillJob?.cancel(); liveId = null
            visitForms.remove(id); withContext(Dispatchers.IO) { forms.delete(id) }
            return
        }
        val chunks = session.results.sortedBy { it.start }
        val t = Transcript(id, wav.name, System.currentTimeMillis(), audioSeconds,
            (SystemClock.elapsedRealtime() - t0) / 1000f, chunks, live = true, model = modelName)
        withContext(Dispatchers.IO) { store.save(t) }
        tlog("transcript complete and saved (${chunks.size} parts), final form pass starts")
        liveTitle = wav.name
        lastResult = t
        refreshHistory()
        liveId = null
        if (autoFill && canFill) fillForm(t) else liveFillJob?.cancel()
    }

    private suspend fun run(title: String, samples: FloatArray) {
        check(transcriber.isLoaded) { "The transcription engine is not ready yet" }
        val audioSeconds = samples.size / AudioDecoder.SAMPLE_RATE.toFloat()
        liveTitle = title
        liveChunks.clear()
        lastResult = null
        val t0 = SystemClock.elapsedRealtime()
        val elapsed = { (SystemClock.elapsedRealtime() - t0) / 1000f }
        status = Status.Transcribing(0, 0, 0f, audioSeconds)
        val chunks = withContext(Dispatchers.Default) {
            transcriber.transcribe(samples, threads) { done, total, chunk ->
                val e = elapsed()
                viewModelScope.launch {  // UI state is touched on the main thread only
                    chunk?.let { liveChunks.add(it) }
                    if (busy) status = Status.Transcribing(done, total, e, audioSeconds)
                }
            }
        }
        if (!busy) return  // cancelled
        val t = Transcript(store.newId(), title, System.currentTimeMillis(), audioSeconds, elapsed(), chunks, model = modelName)
        withContext(Dispatchers.IO) { store.save(t) }
        lastResult = t
        refreshHistory()
        if (autoFill && canFill) fillForm(t)
    }

    fun cancel() {
        transcriber.cancel()
        job?.cancel()
        liveJobs?.let { (a, b) -> a.cancel(); b.cancel() }
        liveJobs = null
        live = null
        if (recorder.isRecording) recorder.stop(null)
        status = Status.Idle
    }

    fun delete(t: Transcript) {
        store.delete(t)
        forms.delete(t.id)
        visitForms.remove(t.id)
        refreshHistory()
    }

    fun rename(t: Transcript, title: String) {
        val n = t.copy(title = title.trim().ifEmpty { t.title })
        store.save(n)
        refreshHistory()
    }

    fun textFile(t: Transcript) = File(store.dir, "${t.id}.txt")

    private fun refreshHistory() {
        history.clear()
        history.addAll(store.list())
    }

    private fun displayName(uri: Uri): String =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "audio"

    override fun onCleared() {
        transcriber.release()
        if (llm != 0L) LlamaNative.free(llm)
        bert?.close()
    }
}
