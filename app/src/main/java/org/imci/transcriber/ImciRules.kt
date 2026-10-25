package org.imci.transcriber

/** One IMCI classification; [colour] Pink (refer urgently) / Yellow / Green / Grey; [because] = item keys. */
data class Classification(val name: String, val colour: String, val because: List<String>)

/**
 * IMCI classification by rules from the form answers (2-59 months) - a Kotlin copy of imci_app_v1/local_app/imci_rules.py
 * (IMCI Chart Booklet 2019 pp. 3-9). Keep the two in sync.
 */
object ImciRules {
    fun classify(values: Map<String, String>): List<Classification> {
        fun v(k: String) = values[k]
        fun answered(vararg keys: String) = keys.any { v(it) != null }
        val out = ArrayList<Classification>()
        fun add(n: String, c: String, b: List<String>) = out.add(Classification(n, c, b))

        val gdsKeys = listOf("o38", "o39", "q16", "q17", "q18")
        val gds = gdsKeys.filter { if (it == "q16") v(it) == "no" else v(it) == "yes" }
        if (gds.isNotEmpty()) add("Very Severe Disease", "Pink", gds)
        else if (answered(*gdsKeys.toTypedArray())) add("No Very Severe Disease", "Green", gdsKeys.filter { v(it) != null })

        if (v("q19") == "yes" || answered("o40", "o42", "o43", "o44")) {
            val fast = v("o40") in setOf("40 or more per minute", "50 or more per minute")
            val severe = gds + listOf("o43", "o44").filter { v(it) == "yes" }
            when {
                severe.isNotEmpty() -> add("Severe Pneumonia", "Pink", severe)
                fast || v("o42") == "yes" -> add("Pneumonia", "Yellow", listOf("o40", "o42").filter { (it == "o40" && fast) || v(it) == "yes" })
                answered("o40", "o42") -> add("No Pneumonia (Cough or Cold)", "Green", listOf("o40", "o42"))
                else -> add("Cough or difficult breathing - signs not assessed", "Grey", listOf("q19"))
            }
        }

        if (v("q21") == "yes") {
            val sev = listOf("o46", "o48", "o49").filter { v(it) == "yes" } + if (v("o51") == "Very slowly (longer than 2 seconds)") listOf("o51") else emptyList()
            val some = listOf("o47", "o48", "o50").filter { v(it) == "yes" } + if (v("o51") == "Slowly") listOf("o51") else emptyList()
            val dk = listOf("o46", "o47", "o48", "o49", "o50", "o51")
            when {
                sev.size >= 2 -> add("Severe Dehydration", "Pink", sev)
                some.size >= 2 -> add("Some Dehydration", "Yellow", some)
                answered(*dk.toTypedArray()) -> add("No Dehydration", "Green", dk.filter { v(it) != null })
                else -> add("Diarrhoea - dehydration not assessed", "Grey", listOf("q21"))
            }
            val dehyd = sev.size >= 2 || some.size >= 2
            if (v("q22") == "yes") add(if (dehyd) "Severe Persistent Diarrhoea" else "Persistent Diarrhoea", if (dehyd) "Pink" else "Yellow", listOf("q22"))
            if (v("q23") == "yes") add("Dysentery", "Yellow", listOf("q23"))
        }

        if (v("q24") == "yes") when {
            gds.isNotEmpty() || v("o53") == "yes" -> add("Very Severe Febrile Disease", "Pink", gds + if (v("o53") == "yes") listOf("o53") else emptyList())
            v("q25") == "no" || v("o52") == "Malaria RDT Negative" -> add("Fever - No Malaria", "Green", listOf("q25", "o52").filter { v(it) != null })
            else -> add("Fever", "Green", listOf("q24"))
        }

        if (v("o57") == "yes" || v("q28") == "yes") when {
            gds.isNotEmpty() || v("o64") == "yes" || v("o62") == "yes" -> add("Severe Complicated Measles", "Pink", gds + listOf("o62", "o64").filter { v(it) == "yes" })
            v("o61") == "yes" || v("o63") == "yes" -> add("Measles With Eye or Mouth Complication", "Yellow", listOf("o61", "o63").filter { v(it) == "yes" })
            else -> add("Measles", "Green", listOf("o57", "q28").filter { v(it) == "yes" })
        }

        when {
            v("o66") == "yes" -> add("Mastoiditis", "Pink", listOf("o66"))
            v("o65") == "yes" || v("q31") == "yes" || v("q30") == "yes" ->
                add(if (v("q32") == "no") "Chronic Ear Infection" else "Acute Ear Infection", "Yellow", listOf("o65", "q30", "q31", "q32").filter { v(it) != null })
            v("q29") == "no" -> add("No Ear Infection", "Green", listOf("q29"))
        }

        val mk = listOf("o67", "o68", "o69", "o70", "o71", "o72")
        when {
            v("o67") == "yes" || v("o68") == "Less than -3 Z" || v("o69") == "Less than 115 mm" -> {
                val comp = v("o67") == "yes" || v("o70") == "yes" || gds.isNotEmpty() || v("o71") == "no" || v("o72") == "yes"
                add(if (comp) "Complicated Severe Acute Malnutrition" else "Uncomplicated Severe Acute Malnutrition", if (comp) "Pink" else "Yellow", mk.filter { v(it) != null })
            }
            v("o68") == "Between -2 to -3 Z" || v("o69") == "Between 115-125 mm" -> add("Moderate Acute Malnutrition", "Yellow", listOf("o68", "o69").filter { v(it) != null })
            answered("o68", "o69") -> add("No Acute Malnutrition", "Green", listOf("o68", "o69"))
        }

        when (v("o73")) {
            "Very Faded" -> add("Severe Anaemia", "Pink", listOf("o73"))
            "Medium Faded" -> add("Anaemia", "Yellow", listOf("o73"))
            "Not Faded" -> add("No Anaemia", "Green", listOf("o73"))
        }
        return out
    }
}
