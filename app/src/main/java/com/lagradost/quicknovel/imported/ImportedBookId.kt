package com.lagradost.quicknovel.imported

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Translator novel ids for imported books (EPUB / TXT).
 *
 * Online novels use "apiName/novelName" ([com.lagradost.quicknovel.translator.TranslatorNovelId]).
 * An imported book has no provider, so its id is derived from the import record: the book's
 * title and author, the same pair that already identifies the import in the library (download
 * id and storage folder). It does not depend on the file path or on where the file was picked
 * from, and re-importing an edited copy of the same book keeps the id, so its stored
 * translations, Story Bible and spend stay attached and changed chapters are detected by their
 * source hash. The short digest keeps two books with the same title but different authors apart.
 *
 * Format: `Imported/<title>#<8 hex>` (title NFC-normalised, whitespace collapsed).
 */
object ImportedBookId {
    const val PREFIX = "Imported/"

    /** Result-page URL scheme for an imported book: `imported://<novelId>`. */
    const val URL_SCHEME = "imported://"

    private val WHITESPACE = Regex("""\s+""")

    fun normalize(text: String?): String =
        Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFC).replace(WHITESPACE, " ").trim()

    fun of(title: String, author: String?): String {
        val t = normalize(title)
        val digest = sha256Hex("${normalize(author)}\u0000$t").take(8)
        return "$PREFIX$t#$digest"
    }

    fun isImported(novelId: String?): Boolean = novelId?.startsWith(PREFIX) == true

    fun resultUrl(novelId: String): String = "$URL_SCHEME$novelId"

    /** The novel id of an `imported://` result URL, or null for any other URL. */
    fun fromResultUrl(url: String?): String? =
        url?.takeIf { it.startsWith(URL_SCHEME) }?.removePrefix(URL_SCHEME)?.takeIf { isImported(it) }

    /** Chapter URL used in the result page's chapter list (unique per chapter). */
    fun chapterUrl(index: Int): String = "imported-chapter://$index"

    /** SHA-256 hex of the imported file's bytes (tells a re-import of the same file from a changed one). */
    fun contentHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(text: String): String = contentHash(text.toByteArray(Charsets.UTF_8))
}
