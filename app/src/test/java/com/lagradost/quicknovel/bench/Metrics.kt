package com.lagradost.quicknovel.bench

/** Local, free metrics for one (model, sample) translation. */
data class Metrics(
    val adherencePct: Double?,
    val termsChecked: Int,
    val termMisses: List<String>,
    val cjkLeak: Int,
    val leakSnippets: List<String>,
    val paragraphFidelity: Double,
    val outputParagraphs: Int,
    val inputParagraphs: Int,
    val genderChecked: Int,
    val genderMismatches: Int,
    val genderDetails: List<String>,
    val englishWords: Int,
    val lengthRatio: Double,
    val probableOmission: Boolean,
    /** First refusal-like line in the output (e.g. a copyright refusal for one chunk), or null. */
    val refusal: String? = null,
)

object MetricsCalculator {
    private val HE = setOf("he", "him", "his", "himself")
    private val SHE = setOf("she", "her", "hers", "herself")
    private val WORD = Regex("""[A-Za-z]+(?:['’][A-Za-z]+)*""")

    fun isCjk(c: Char): Boolean {
        val x = c.code
        return x in 0x4E00..0x9FFF || x in 0x3400..0x4DBF || x in 0xF900..0xFAFF || // Han
                x in 0x3040..0x309F || // Hiragana
                x in 0x30A0..0x30FF || x in 0x31F0..0x31FF || x in 0xFF66..0xFF9F || // Katakana
                x in 0xAC00..0xD7AF || x in 0x1100..0x11FF || x in 0x3130..0x318F // Hangul
    }

    /** Case-insensitive whole-phrase match allowing possessive 's and plural s. */
    fun containsTerm(text: String, english: String): Boolean {
        val phrase = english.trim()
        if (phrase.isEmpty()) return false
        val parts = phrase.split(Regex("""\s+""")).joinToString("""[\s\-]+""") { Regex.escape(it) }
        val re = Regex("""(?<![\p{L}\p{N}])$parts(?:['’]s|s|es|['’])?(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        return re.containsMatchIn(text)
    }

    fun sentences(text: String): List<String> =
        text.split(Regex("""(?<=[.!?…])["'”’)\]]*\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    fun compute(sample: Sample, bible: Bible, output: String): Metrics {
        // 1. glossary adherence
        val relevant = bible.entries.filter { e -> sample.text.contains(e.source) || e.aliases.any { it.isNotEmpty() && sample.text.contains(it) } }
            .distinctBy { it.english.lowercase() }
        val misses = relevant.filterNot { containsTerm(output, it.english) }.map { "${it.source} → ${it.english}" }
        val adherence = if (relevant.isEmpty()) null else 100.0 * (relevant.size - misses.size) / relevant.size

        // 2. CJK leak
        val leakCount = output.count { isCjk(it) }
        val snippets = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]+""").findAll(output)
            .map { it.value }.distinct().take(10).toList()

        // 3. paragraphs
        val outParas = output.lines().count { it.isNotBlank() }
        val inParas = sample.paragraphs.size
        val fidelity = if (inParas == 0) 0.0 else outParas.toDouble() / inParas

        // 4. gender consistency
        val chars = bible.characters.filter { it.gender == "male" || it.gender == "female" }
        // Name parts shared by several characters (family names) are not distinctive.
        val partCounts = bible.characters.flatMap { nameForms(it.english).drop(1) }.groupingBy { it.lowercase() }.eachCount()
        val allNames = bible.characters.associateWith { c ->
            nameForms(c.english).filterIndexed { i, form -> i == 0 || partCounts[form.lowercase()] == 1 }
        }
        var checked = 0
        var mismatches = 0
        val details = mutableListOf<String>()
        for (s in sentences(output)) {
            val present = allNames.filter { (_, forms) -> forms.any { containsTerm(s, it) } }.keys
            if (present.size != 1) continue // none, or ambiguous (several named characters)
            val c = present.first()
            if (c !in chars) continue
            val words = WORD.findAll(s.lowercase()).map { it.value }.toList()
            val he = words.count { it in HE }
            val she = words.count { it in SHE }
            if (he + she == 0) continue
            checked++
            val wrong = if (c.gender == "male") she else he
            if (wrong > 0) {
                mismatches += wrong
                if (details.size < 15) details.add("${c.english} (${c.gender}): \"${s.take(160)}\"")
            }
        }

        // 5. length ratio
        val words = WORD.findAll(output).count()
        val ratio = if (sample.chars == 0) 0.0 else words.toDouble() / sample.chars

        val refusal = output.lines().firstOrNull { REFUSAL.containsMatchIn(it) }?.trim()?.take(200)

        return Metrics(adherence, relevant.size, misses, leakCount, snippets, fidelity, outParas, inParas,
            checked, mismatches, details, words, ratio, ratio < 0.25, refusal)
    }

    private val REFUSAL = Regex(
        """\bI(?: am|['’]m)? (?:can(?:'|’|no)t|cannot|won(?:'|’)t|unable to|not able to|not going to) (?:provide|produce|translate|help|assist|reproduce|comply|fulfil|do)|\bcopyrighted\b|\bWhat I can do instead\b|\bI(?:'|’)m sorry, but\b|\bAs an AI\b""",
        RegexOption.IGNORE_CASE
    )

    /** Full name plus distinctive parts (>= 4 letters), e.g. "Rudeus Greyrat" -> [Rudeus Greyrat, Rudeus, Greyrat]. */
    private fun nameForms(english: String): List<String> {
        val parts = english.split(Regex("""\s+""")).filter { it.length >= 4 && it.first().isUpperCase() }
        return (listOf(english) + parts).distinct()
    }
}
