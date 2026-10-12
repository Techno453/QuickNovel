package com.lagradost.quicknovel.translator

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Fingerprint of a chapter's source text, stored with its translation (`translated_chapters.source_hash`)
 * so a changed source can be detected when the source is fetched anyway.
 *
 * Normalisation (so markup and whitespace noise from the site do not count as a change): HTML
 * to plain text ([TranslationEngine.htmlToPlainText]), Unicode NFC, every run of whitespace
 * inside a line collapsed to one space, lines trimmed, blank lines dropped, joined with "\n".
 */
object SourceHash {
    /** Bump when [normalize] changes (stored hashes of the old version then no longer match). */
    const val VERSION = 1

    private val WHITESPACE = Regex("""[\s　 ]+""")

    fun normalize(source: String): String {
        val plain = Normalizer.normalize(TranslationEngine.htmlToPlainText(source), Normalizer.Form.NFC)
        return plain.replace("\r\n", "\n").split('\n')
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** SHA-256 hex of [normalize]d [source]. */
    fun of(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalize(source).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    enum class Check {
        /** No stored translation. */
        NO_TRANSLATION,
        /** Stored hash equals the current source's hash. */
        MATCH,
        /** Stored translation has no hash (from before DB v6): treated as valid. */
        UNKNOWN,
        /** The source changed since the translation was made. */
        CHANGED,
    }

    /** Pure comparison: [storedHash] null = no stored translation, "" = unknown. */
    fun compare(storedHash: String?, currentHash: String): Check = when {
        storedHash == null -> Check.NO_TRANSLATION
        storedHash.isEmpty() -> Check.UNKNOWN
        storedHash == currentHash -> Check.MATCH
        else -> Check.CHANGED
    }
}
