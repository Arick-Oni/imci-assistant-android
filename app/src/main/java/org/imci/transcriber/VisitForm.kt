package org.imci.transcriber

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The IMCI form of one visit (one transcript): what the AI answered, the answers as they stand now (AI or edited by
 * the health worker) and whether the worker confirmed them. Kept next to the transcript so every correction is
 * also test data for the next model.
 */
data class VisitForm(
    val ai: Map<String, AiAnswer> = emptyMap(),
    val aiModel: String? = null,
    val aiSeconds: Float = 0f,
    val lines: List<String> = emptyList(),           // the numbered transcript lines the AI read (L1 = index 0)
    val values: Map<String, String> = emptyMap(),
    val sources: Map<String, String> = emptyMap(),   // key -> "ai" | "worker"
    val edits: Int = 0,
    val reviewedMs: Long? = null,
) {
    val filled get() = aiModel != null
    val classes get() = ImciRules.classify(values)
}

/** Visit forms as JSON under Android/data/org.imci.transcriber/files/visits/<transcript id>.json. */
class VisitFormStore(context: Context) {
    val dir: File = File(context.getExternalFilesDir(null), "visits").apply { mkdirs() }

    fun file(id: String) = File(dir, "$id.json")

    fun load(id: String): VisitForm? = runCatching {
        val f = file(id)
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        val ai = j.optJSONObject("ai")?.let { o ->
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONObject(k)
                AiAnswer(if (a.isNull("value")) null else a.getString("value"), a.optDouble("confidence", 0.0).toFloat(),
                    if (a.has("from")) a.getInt("from")..a.getInt("to") else null)
            }
        }.orEmpty()
        fun strMap(o: JSONObject?) = o?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty()
        VisitForm(ai, if (j.isNull("ai_model")) null else j.optString("ai_model"), j.optDouble("ai_seconds", 0.0).toFloat(),
            j.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            strMap(j.optJSONObject("values")), strMap(j.optJSONObject("sources")), j.optInt("edits", 0),
            if (j.has("reviewed_ms") && !j.isNull("reviewed_ms")) j.getLong("reviewed_ms") else null)
    }.getOrNull()

    fun save(id: String, v: VisitForm) {
        val ai = JSONObject()
        v.ai.forEach { (k, a) ->
            ai.put(k, JSONObject().put("value", a.value ?: JSONObject.NULL).put("confidence", a.confidence.toDouble()).apply {
                a.lines?.let { put("from", it.first); put("to", it.last) }
            })
        }
        val j = JSONObject().put("ai", ai).put("ai_model", v.aiModel ?: JSONObject.NULL).put("ai_seconds", v.aiSeconds.toDouble())
            .put("lines", JSONArray(v.lines)).put("values", JSONObject(v.values)).put("sources", JSONObject(v.sources))
            .put("edits", v.edits).put("reviewed_ms", v.reviewedMs ?: JSONObject.NULL)
            .put("classification", JSONArray(v.classes.map { JSONObject().put("name", it.name).put("colour", it.colour) }))
        file(id).writeText(j.toString(2))
    }

    fun delete(id: String) { file(id).delete() }
}
