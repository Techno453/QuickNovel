package com.lagradost.quicknovel.ui.common

import androidx.compose.runtime.Immutable
import com.lagradost.quicknovel.util.PosterMemoStore

/**
 * What a card, the result page or the preview sheet shows as a book's cover: a saved file
 * (`poster.jpg` of a downloaded or imported book) or a web url plus the headers that url needs.
 *
 * Equality is by value, so Compose's `remember(model)` and the image request survive
 * recomposition and only change when the cover does; the image caches are keyed on [data]
 * (the url or file path), never on the headers. See docs/images.md.
 */
@Immutable
data class PosterModel(
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val file: String? = null,
) {
    /** What the image loader fetches: the saved file first, else the url. */
    val data: String? get() = file ?: url

    /** Memory and disk cache key. Headers are deliberately not part of it. */
    val cacheKey: String? get() = data

    /** No cover known: show the placeholder without asking the loader. */
    val isEmpty: Boolean get() = data == null

    companion object {
        val Empty = PosterModel()

        /**
         * False for what a site sends instead of a cover (an HTML or JSON error page, nothing).
         * Image formats are not listed one by one, anything else counts as an image.
         */
        fun looksLikeImage(head: ByteArray): Boolean {
            if (head.isEmpty()) return false
            val first = head.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() && it != 0xEF.toByte() && it != 0xBB.toByte() && it != 0xBF.toByte() }
                ?: return false
            return first != '<'.code.toByte() && first != '{'.code.toByte() && first != '['.code.toByte()
        }

        /** [looksLikeImage] on the first bytes of a saved cover; false when it cannot be read. */
        fun looksLikeImage(file: java.io.File): Boolean = try {
            if (!file.isFile || file.length() == 0L) false
            else file.inputStream().use { input ->
                val buf = ByteArray(16)
                val n = input.read(buf)
                n > 0 && looksLikeImage(buf.copyOf(n))
            }
        } catch (_: Exception) {
            false
        }

        /**
         * Picks the cover: a saved file that exists, else the item's own url, else the cover
         * remembered from when the book's page (or another listing) was loaded.
         */
        fun resolve(
            posterUrl: String?,
            headers: Map<String, String>?,
            savedFile: String? = null,
            memo: PosterMemoStore.Entry? = null,
        ): PosterModel = when {
            !savedFile.isNullOrBlank() -> PosterModel(file = savedFile)
            !posterUrl.isNullOrBlank() -> PosterModel(url = posterUrl.trim(), headers = headers.orEmpty())
            memo != null -> PosterModel(url = memo.posterUrl, headers = memo.headers.orEmpty())
            else -> Empty
        }
    }
}

/** Header rules shared by every cover request (docs/images.md). */
object PosterHeaders {
    const val USER_AGENT_HEADER = "User-Agent"

    /**
     * [base] overlaid with [override]; names compare case-insensitively ("referer" replaces
     * "Referer") and the overriding name's spelling is kept. Blank values are dropped.
     */
    fun merge(base: Map<String, String>?, override: Map<String, String>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val names = HashMap<String, String>()
        fun add(map: Map<String, String>?) {
            map?.forEach { (name, value) ->
                if (name.isBlank() || value.isBlank()) return@forEach
                val lower = name.lowercase()
                names[lower]?.let { out.remove(it) }
                names[lower] = name
                out[name] = value
            }
        }
        add(base)
        add(override)
        return out
    }

    /** The app's browser user agent unless the source sent its own. */
    fun withDefaults(headers: Map<String, String>?, userAgent: String): Map<String, String> =
        merge(mapOf(USER_AGENT_HEADER to userAgent), headers)
}
