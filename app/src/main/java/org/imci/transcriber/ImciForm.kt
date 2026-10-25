package org.imci.transcriber

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One form question (imci_official_form_schema.json, 2-59 months). */
data class FormItem(
    val key: String,
    val text: String,
    val textBn: String,
    val isQuestion: Boolean,          // "Ask" (question) or "Look" (observation)
    val answerType: String,           // yes_no | choice | number | free_text
    val options: List<String>,
) {
    val isYesNo get() = answerType == "yes_no"
}

/** A question inside a section, shown only when [showIf] holds (imci_form_hierarchy.json). */
data class SectionItem(val key: String, val showIf: JSONObject?)

data class Section(
    val area: String,
    val title: String,
    val titleBn: String,
    val gate: String?,
    val items: List<SectionItem>,
    val collapsed: Boolean,
) {
    /** Gate first, then the follow-ups in form order. */
    val keys get() = listOfNotNull(gate) + items.map { it.key }
}

/** The IMCI form for children 2-59 months: questions + the form's skip rules, read from the app assets. */
class ImciForm(context: Context) {
    val items: Map<String, FormItem>
    val sections: List<Section>

    init {
        val schema = JSONObject(context.assets.open("imci_official_form_schema.json").bufferedReader().readText())
        val its = LinkedHashMap<String, FormItem>()
        val areas = schema.getJSONArray("areas")
        for (i in 0 until areas.length()) {
            val a = areas.getJSONObject(i)
            if (a.getString("age_group") != AGE_GROUP) continue
            val arr = a.getJSONArray("items")
            for (j in 0 until arr.length()) {
                val it = arr.getJSONObject(j)
                val opts = it.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.getString(it) } }.orEmpty()
                its[it.getString("key")] = FormItem(it.getString("key"), it.getString("text"), it.optString("text_bn", ""),
                    it.optString("kind") == "question", it.optString("answer_type", "yes_no"), opts)
            }
        }
        items = its
        val hier = JSONObject(context.assets.open("imci_form_hierarchy.json").bufferedReader().readText())
            .getJSONObject("sections").getJSONArray(AGE_GROUP)
        sections = (0 until hier.length()).map { i ->
            val s = hier.getJSONObject(i)
            val arr = s.getJSONArray("items")
            Section(s.getString("area"), s.getString("title"), s.optString("title_bn", ""),
                if (s.has("gate")) s.getString("gate") else null,
                (0 until arr.length()).map { j ->
                    when (val x = arr.get(j)) {
                        is String -> SectionItem(x, null)
                        else -> (x as JSONObject).let { SectionItem(it.getString("key"), it.optJSONObject("show_if")) }
                    }
                },
                s.optBoolean("collapsed", false))
        }
    }

    /** The question's show_if condition for [key] (null = always shown inside its section). */
    fun showIf(key: String): JSONObject? = sections.firstNotNullOfOrNull { s -> s.items.firstOrNull { it.key == key }?.showIf }

    /** Is the question shown, given the current answers? (same as cond_ok in hierarchy_fill.py) */
    fun shown(key: String, answers: Map<String, String>): Boolean = condOk(showIf(key), answers)

    companion object {
        const val AGE_GROUP = "2-59 months"

        fun condOk(cond: JSONObject?, answers: Map<String, String>): Boolean {
            if (cond == null) return true
            if (cond.has("any")) {
                val any: JSONArray = cond.getJSONArray("any")
                return (0 until any.length()).any { condOk(any.getJSONObject(it), answers) }
            }
            return cond.keys().asSequence().all { k ->
                val allowed = cond.getJSONArray(k)
                (0 until allowed.length()).any { allowed.getString(it) == answers[k] }
            }
        }
    }
}
