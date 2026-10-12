package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/** Parsed model output for one chunk. */
data class ParsedChunkOutput(
    /** The English translation only (tail removed, fences/echoes/legacy markers cleaned). */
    val translation: String,
    val terms: List<DiscoveredTerm> = emptyList(),
    /** 2–4 sentence summary of the chunk ("" if the section is missing). */
    val summary: String = "",
    /** Source-language names from the summary's "Characters:" line. */
    val summaryCharacters: List<String> = emptyList(),
    val hasTermsSection: Boolean = false,
    val hasSummarySection: Boolean = false,
)

/** Result of the "Analyze novel" pre-pass for one excerpt. */
data class BibleExtraction(
    val premise: String,
    val terms: List<DiscoveredTerm>,
)

/**
 * The structured tail protocol:
 *
 * ```
 * <English translation>
 * ===TERMS===
 * [{"source":"","english":"","category":"","gender":"","notes":"","confidence":0.0}]
 * ===SUMMARY===
 * <2–4 sentences>
 * Characters: <source names, comma-separated>
 * ```
 *
 * Parsing is tolerant: sections may be missing or reordered, wrapped in code fences or
 * markdown emphasis; malformed JSON falls back to per-object parsing and then to per-field
 * regexes; legacy inline `[NEW: source → english]` markers are still accepted.
 */
object TranslationProtocol {
    const val TERMS_MARKER = "===TERMS==="
    const val SUMMARY_MARKER = "===SUMMARY==="

    /** Matches [NEW: source → suggestion] with →, ->, =>, ⇒, ＞ arrows and full-width brackets/colons. */
    val NEW_TERM_PATTERN = Regex(
        """[\[［]\s*NEW\s*[:：]\s*(.+?)\s*(?:→|->|－>|-＞|－＞|=>|⇒|＞|>)\s*(.+?)\s*[\]］]"""
    )

    /** Legacy markers get this confidence, i.e. they go to review rather than straight into the bible. */
    const val LEGACY_MARKER_CONFIDENCE = 0.5f

    private val MARKER_LINE = Regex(
        """^[ \t]*[*_#>`]*[ \t]*={2,}[ \t]*(TERMS|SUMMARY)[ \t]*={2,}[ \t]*[*_`]*[ \t]*:?[ \t]*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )
    private val FENCE_LINE = Regex("""^\s*```[\w-]*\s*$""")
    private val BARE_TERMS_ARRAY = Regex("""\n\s*\[\s*\{\s*["']source["']""")
    private val OBJECT = Regex("""\{[^{}]*\}""")
    private val CHARACTERS_LINE = Regex(
        """^\s*[*_]*\s*(?:Characters(?:\s+present)?|Characters\s+in\s+this\s+passage)\s*[*_]*\s*[:：]\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val PREMISE_FIELD = Regex(""""premise"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()
        .also { it.registerKotlinModule() }

    // ------------------------------------------------------------------------------------------
    // Translation output
    // ------------------------------------------------------------------------------------------

    fun parse(raw: String): ParsedChunkOutput {
        val text = raw.replace("\r\n", "\n")
        val markers = MARKER_LINE.findAll(text).toList()

        var translationPart: String
        var termsPart: String? = null
        var summaryPart: String? = null

        if (markers.isEmpty()) {
            translationPart = text
        } else {
            translationPart = text.substring(0, markers.first().range.first)
            markers.forEachIndexed { i, m ->
                val end = markers.getOrNull(i + 1)?.range?.first ?: text.length
                val body = text.substring(minOf(m.range.last + 1, end), end)
                when (m.groupValues[1].uppercase()) {
                    "TERMS" -> if (termsPart == null) termsPart = body
                    "SUMMARY" -> if (summaryPart == null) summaryPart = body
                }
            }
        }

        // No ===TERMS=== marker, but a bare JSON array of terms at the end of the output.
        if (termsPart == null) {
            BARE_TERMS_ARRAY.findAll(translationPart).lastOrNull()?.let { m ->
                termsPart = translationPart.substring(m.range.first)
                translationPart = translationPart.substring(0, m.range.first)
            }
        }

        val legacy = extractNewTerms(translationPart).map { (source, english) ->
            DiscoveredTerm(source = source, english = english, confidence = LEGACY_MARKER_CONFIDENCE)
        }
        val translation = TranslationEngine.cleanModelOutput(stripNewTermMarkers(translationPart))

        val terms = termsPart?.let { parseTerms(it) }.orEmpty() + legacy
        val (summary, characters) = summaryPart?.let { parseSummary(it) } ?: ("" to emptyList())

        return ParsedChunkOutput(
            translation = translation,
            terms = terms,
            summary = summary,
            summaryCharacters = characters,
            hasTermsSection = termsPart != null,
            hasSummarySection = summaryPart != null,
        )
    }

    /** Extracts (source, suggestion) pairs from [NEW: …] markers. */
    fun extractNewTerms(text: String): List<Pair<String, String>> =
        NEW_TERM_PATTERN.findAll(text).map { m ->
            m.groupValues[1].trim() to m.groupValues[2].trim()
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toList()

    /** Replaces [NEW: source → suggestion] markers with just the suggestion. */
    fun stripNewTermMarkers(text: String): String =
        text.replace(NEW_TERM_PATTERN) { it.groupValues[2].trim() }

    private fun stripFences(text: String): String =
        text.lines().filterNot { FENCE_LINE.matches(it) }.joinToString("\n").trim()

    /** Summary text and the "Characters:" names. */
    fun parseSummary(section: String): Pair<String, List<String>> {
        val lines = stripFences(section).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val characters = mutableListOf<String>()
        val prose = mutableListOf<String>()
        for (line in lines) {
            val m = CHARACTERS_LINE.find(line)
            if (m != null) {
                m.groupValues[1].split(',', '，', '、', ';', '；')
                    .map { it.trim().trim('*', '_', '"', '\'', '「', '」', '.', '。') }
                    .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
                    .forEach { characters += it.substringBefore('(').substringBefore('（').trim() }
            } else if (characters.isEmpty()) {
                // Anything after the Characters line is trailing chatter; ignore it.
                prose += line.removePrefix("Summary:").removePrefix("**Summary:**").trim()
            }
        }
        return prose.joinToString(" ").trim().take(MAX_SUMMARY_CHARS) to characters.filter { it.isNotEmpty() }.distinct()
    }

    private const val MAX_SUMMARY_CHARS = 1200

    /** Parses the JSON array of terms, falling back to per-object and per-field parsing. */
    fun parseTerms(section: String): List<DiscoveredTerm> {
        val text = stripFences(section)
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val node = mapper.readTree(text.substring(start, end + 1))
                if (node.isArray) return node.mapNotNull { termFromNode(it) }
            } catch (e: Exception) {
                // fall through to per-object parsing
            }
        }
        return OBJECT.findAll(text).mapNotNull { m ->
            try {
                termFromNode(mapper.readTree(m.value))
            } catch (e: Exception) {
                termFromFields(m.value)
            }
        }.toList()
    }

    private fun termFromNode(node: JsonNode?): DiscoveredTerm? {
        if (node == null || !node.isObject) return null
        fun str(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> node.get(k)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
        val source = str("source", "original", "chinese", "japanese", "korean", "term")
        val english = str("english", "translation", "rendering", "en")
        if (source.isEmpty() || english.isEmpty()) return null
        val aliasNode = node.get("aliases")
        val aliases = when {
            aliasNode == null || aliasNode.isNull -> emptyList()
            aliasNode.isArray -> aliasNode.mapNotNull { it.asText()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            else -> aliasNode.asText().split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return DiscoveredTerm(
            source = source,
            english = english,
            category = str("category", "type"),
            gender = str("gender", "sex"),
            notes = str("notes", "note", "description", "role"),
            confidence = parseConfidence(node.get("confidence")?.asText()),
            aliases = aliases,
            proper = parseProper(node),
        )
    }

    /**
     * The analysis pre-pass's `"proper"` flag (true/false, also as a string), or a `"kind"` of
     * "generic"/"common"/"role" (false) vs "proper"/"name"/"term" (true); null when absent or unclear.
     */
    internal fun parseProper(node: JsonNode): Boolean? {
        node.get("proper")?.takeIf { !it.isNull }?.let { p ->
            if (p.isBoolean) return p.booleanValue()
            when (p.asText().trim().lowercase()) {
                "true", "yes", "1" -> return true
                "false", "no", "0" -> return false
            }
        }
        return when (node.get("kind")?.takeIf { !it.isNull }?.asText()?.trim()?.lowercase()) {
            "generic", "common", "common noun", "role", "description", "ordinary" -> false
            "proper", "proper noun", "name", "term", "terminology" -> true
            else -> null
        }
    }

    private fun termFromFields(obj: String): DiscoveredTerm? {
        fun field(name: String): String =
            Regex("""["']?$name["']?\s*:\s*["“]([^"”\n]*)["”]""", RegexOption.IGNORE_CASE).find(obj)?.groupValues?.get(1)?.trim().orEmpty()
        val source = field("source")
        val english = field("english")
        if (source.isEmpty() || english.isEmpty()) return null
        val confidence = Regex("""["']?confidence["']?\s*:\s*["']?([\w.]+)""", RegexOption.IGNORE_CASE)
            .find(obj)?.groupValues?.get(1)
        return DiscoveredTerm(
            source = source,
            english = english,
            category = field("category"),
            gender = field("gender"),
            notes = field("notes"),
            confidence = parseConfidence(confidence),
        )
    }

    /** Numbers (0–1 or percentages) and words (high / medium / low); missing → 0.5. */
    fun parseConfidence(raw: String?): Float {
        val s = raw?.trim()?.lowercase()?.removeSuffix("%") ?: return 0.5f
        s.toFloatOrNull()?.let { v ->
            val f = if (v > 1f) v / 100f else v
            return if (f.isNaN()) 0.5f else f.coerceIn(0f, 1f)
        }
        return when (s) {
            "very high", "certain" -> 0.95f
            "high" -> 0.85f
            "medium", "moderate" -> 0.6f
            "low" -> 0.3f
            "very low" -> 0.1f
            else -> 0.5f
        }
    }

    // ------------------------------------------------------------------------------------------
    // Analysis pre-pass output
    // ------------------------------------------------------------------------------------------

    /** Parses `{"premise": "...", "terms": [...]}` (tolerant, same fallbacks as [parseTerms]). */
    fun parseAnalysis(raw: String): BibleExtraction {
        val text = stripFences(raw.replace("\r\n", "\n"))
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val node = mapper.readTree(text.substring(start, end + 1))
                if (node.isObject) {
                    val premise = node.get("premise")?.asText()?.trim().orEmpty()
                    val termsNode = node.get("terms") ?: node.get("characters")
                    val terms = termsNode?.takeIf { it.isArray }?.mapNotNull { termFromNode(it) }.orEmpty()
                    return BibleExtraction(premise, terms)
                }
            } catch (e: Exception) {
                // fall through
            }
        }
        val premise = PREMISE_FIELD.find(text)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\n", " ")?.trim().orEmpty()
        val termsStart = text.indexOf('[')
        val terms = if (termsStart >= 0) parseTerms(text.substring(termsStart)) else emptyList()
        return BibleExtraction(premise, terms)
    }
}
