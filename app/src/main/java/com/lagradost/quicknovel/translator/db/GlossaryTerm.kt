package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One Story Bible entry: a character, place, sect, technique, rank, item... and its fixed
 * English rendering. Characters also carry a gender (for pronouns when the source omits the
 * subject) and free-text notes (role, relationships, speech style) that go into the prompt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Entity(
    tableName = "glossary_terms",
    indices = [Index("novel_id")]
)
data class GlossaryTerm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: String = "",
    /**
     * The source-language term (Chinese, Japanese or Korean). The column and JSON key keep
     * the historical name "chinese" for backward compatibility with existing DBs and exports.
     */
    @ColumnInfo(name = "chinese") @param:JsonProperty("chinese") @get:JsonProperty("chinese") val source: String,
    val english: String,
    val category: String = CATEGORY_OTHER, // see CATEGORIES
    val description: String = "",
    val aliases: String = "[]", // JSON array of alternate source-language strings
    /** "male", "female", "neutral" or "unknown" (see [GENDERS]). */
    @ColumnInfo(defaultValue = GENDER_UNKNOWN) val gender: String = GENDER_UNKNOWN,
    /** Role, relationships, speech style — rendered into the prompt next to the name. */
    @ColumnInfo(defaultValue = "") val notes: String = "",
    /** Chapter index where the term was first seen (null = unknown / manual). */
    @ColumnInfo(name = "first_chapter") val firstChapter: Int? = null,
    /** True when the translator or the analysis pre-pass added the term without review. */
    @ColumnInfo(name = "auto_added", defaultValue = "0") val autoAdded: Boolean = false,
    /** User-confirmed: never changed by automatic updates. */
    @ColumnInfo(defaultValue = "0") val locked: Boolean = false,
) {
    @get:JsonIgnore
    val isCharacter: Boolean get() = category == CATEGORY_CHARACTER

    /** Notes for display / prompts; falls back to the legacy description. */
    @get:JsonIgnore
    val effectiveNotes: String get() = notes.ifBlank { description }

    companion object {
        const val CATEGORY_CHARACTER = "character"
        const val CATEGORY_OTHER = "other"

        /** Category order used by the UI and the prompt (characters first). */
        val CATEGORIES = listOf(
            CATEGORY_CHARACTER, "location", "sect", "rank", "technique", "artifact", "creature", "title", CATEGORY_OTHER
        )

        const val GENDER_MALE = "male"
        const val GENDER_FEMALE = "female"
        const val GENDER_NEUTRAL = "neutral"
        const val GENDER_UNKNOWN = "unknown"
        val GENDERS = listOf(GENDER_MALE, GENDER_FEMALE, GENDER_NEUTRAL, GENDER_UNKNOWN)

        /** Maps loose model/user input to one of [CATEGORIES]. */
        fun normalizeCategory(raw: String?): String {
            val c = raw?.trim()?.lowercase().orEmpty()
            if (c in CATEGORIES) return c
            return when {
                c.isEmpty() -> CATEGORY_OTHER
                c.startsWith("char") || c in setOf("person", "people", "name", "npc", "protagonist") -> CATEGORY_CHARACTER
                c.startsWith("loc") || c in setOf("place", "places", "city", "region", "country", "world") -> "location"
                c in setOf("faction", "factions", "organization", "organisation", "clan", "family", "guild", "school", "group", "sects") -> "sect"
                c in setOf("realm", "level", "cultivation", "stage", "grade", "ranks") -> "rank"
                c in setOf("skill", "skills", "spell", "art", "martial art", "ability", "techniques", "magic") -> "technique"
                c in setOf("item", "items", "weapon", "treasure", "pill", "object", "artifacts", "artefact") -> "artifact"
                c in setOf("beast", "monster", "species", "race", "animal", "creatures") -> "creature"
                c in setOf("honorific", "address", "titles", "position") -> "title"
                else -> CATEGORY_OTHER
            }
        }

        /** Maps loose model/user input to one of [GENDERS]. */
        fun normalizeGender(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "male", "m", "man", "boy", "he", "him", "masculine" -> GENDER_MALE
            "female", "f", "woman", "girl", "she", "her", "feminine" -> GENDER_FEMALE
            "neutral", "n", "none", "it", "they", "nonbinary", "non-binary", "n/a", "na", "object" -> GENDER_NEUTRAL
            else -> GENDER_UNKNOWN
        }

        /** Short badge for the UI: ♂ / ♀ / ⚲ / ?. */
        fun genderBadge(gender: String): String = when (gender) {
            GENDER_MALE -> "♂"
            GENDER_FEMALE -> "♀"
            GENDER_NEUTRAL -> "⚲"
            else -> "?"
        }
    }
}
