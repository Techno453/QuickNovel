package com.lagradost.quicknovel.bench

data class JudgeScore(
    val accuracy: Double?,
    val fluency: Double?,
    val terminology: Double?,
    val note: String,
    val usd: Double,
    val error: String? = null,
) {
    val average: Double?
        get() = listOfNotNull(accuracy, fluency, terminology).takeIf { it.size == 3 }?.average()
}

/** Optional LLM judge: one cheap call per (sample, model). */
class Judge(private val api: BenchOpenAi, private val model: ModelSpec) {
    fun score(sample: Sample, bible: Bible, translation: String): JudgeScore {
        val glossary = bible.entries.filter { sample.text.contains(it.source) }.take(25)
            .joinToString("\n") { "${it.source} = ${it.english}" + if (it.gender != "unknown" && it.category == "character") " (${it.gender})" else "" }
        val system = "You are a strict bilingual editor grading machine translations of web novels into English. Reply with a single JSON object only."
        val user = """
Grade the English translation of this ${BibleSeeder.langName(sample.lang)} excerpt.

Scores are integers 1-10:
- accuracy: meaning preserved, nothing omitted or invented
- fluency: natural, readable English prose
- terminology: names and terms follow the glossary and stay consistent; correct pronouns for characters
Return JSON: {"accuracy": n, "fluency": n, "terminology": n, "note": "<one sentence on the biggest problem>"}

Glossary:
$glossary

Source:
${sample.text}

Translation:
$translation
""".trimIndent()
        val reply = api.chat(model, "judge", sample.id, system, user, maxCompletionTokens = 400, jsonObject = true)
        val usd = reply.call?.usd ?: 0.0
        if (reply.error != null) return JudgeScore(null, null, null, "", usd, reply.error)
        val node = runCatching { benchMapper.readTree(reply.content) }.getOrNull()
            ?: return JudgeScore(null, null, null, "", usd, "judge JSON unparseable")
        fun num(f: String) = node.path(f).takeIf { it.isNumber || it.isTextual }?.asText()?.toDoubleOrNull()?.coerceIn(1.0, 10.0)
        return JudgeScore(num("accuracy"), num("fluency"), num("terminology"), node.path("note").asText(""), usd)
    }
}
