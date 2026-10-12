package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.NovelContext

/**
 * JSON export / import of a novel's Story Bible.
 *
 * Export format (v3):
 * ```
 * {"format":"quicknovel-story-bible","version":3,
 *  "context":{"premise":"","styleNotes":"","nameStyle":"","honorificStyle":""},
 *  "terms":[{"chinese":"林风","english":"Lin Feng","category":"character","gender":"male",...}]}
 * ```
 * Import also accepts the old format (a bare array of terms, e.g. pre-v3 exports and presets).
 * Missing fields take their defaults; unknown fields are ignored.
 */
object StoryBibleJson {
    const val FORMAT = "quicknovel-story-bible"
    const val VERSION = 3

    data class Export(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val context: NovelContext? = null,
        val terms: List<GlossaryTerm> = emptyList(),
    )

    data class Imported(val terms: List<GlossaryTerm>, val context: NovelContext?)

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun export(terms: List<GlossaryTerm>, context: NovelContext?): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            Export(
                context = context?.copy(novelId = ""),
                terms = terms.map { it.copy(id = 0, novelId = "") },
            )
        )

    /**
     * Parses an export for [novelId]. Terms get id = 0 and the given novel id; blank entries are
     * dropped, categories / genders normalized, duplicates (same source) collapsed (last wins).
     * @throws IllegalArgumentException if the text is not a Story Bible / glossary export
     */
    fun import(json: String, novelId: String): Imported {
        val root: JsonNode = try {
            mapper.readTree(json.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Not valid JSON: ${e.message}", e)
        } ?: throw IllegalArgumentException("Empty input")

        val termsNode: JsonNode
        var context: NovelContext? = null
        when {
            root.isArray -> termsNode = root
            root.isObject && root.get("terms")?.isArray == true -> {
                termsNode = root.get("terms")
                root.get("context")?.takeIf { it.isObject }?.let { node ->
                    context = mapper.treeToValue(node, NovelContext::class.java).copy(novelId = novelId)
                }
            }
            else -> throw IllegalArgumentException("Expected a JSON array of terms or a Story Bible export")
        }

        val terms = termsNode.mapNotNull { node ->
            try {
                mapper.treeToValue(node, GlossaryTerm::class.java)
            } catch (e: Exception) {
                null
            }
        }.map {
            it.copy(
                id = 0,
                novelId = novelId,
                source = it.source.trim(),
                english = it.english.trim(),
                category = GlossaryTerm.normalizeCategory(it.category),
                gender = GlossaryTerm.normalizeGender(it.gender),
                aliases = TermAliases.encode(TermAliases.parse(it.aliases)),
            )
        }.filter { it.source.isNotEmpty() && it.english.isNotEmpty() }
            .associateBy { it.source }.values.toList()

        return Imported(terms, context)
    }
}
