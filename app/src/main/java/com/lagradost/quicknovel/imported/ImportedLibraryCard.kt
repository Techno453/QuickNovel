package com.lagradost.quicknovel.imported

import java.security.MessageDigest

/**
 * Pure rules for how an imported book looks in the library (docs/translation-pipeline.md,
 * "Imported books"): its chapter count and its placeholder cover colours.
 */
object ImportedLibraryCard {
    /**
     * The chapter count a library card shows. The download entry of an import always says one
     * "chapter" (the whole file), so the import record's count (the [EpubChapterPlan] / TXT split)
     * wins whenever it is known.
     */
    fun chapterCount(recordChapterCount: Int?, downloadTotal: Long?): Long? =
        recordChapterCount?.takeIf { it > 0 }?.toLong() ?: downloadTotal

    /**
     * Muted backgrounds that all carry white text at a contrast of at least 4.5:1, so the
     * title stays readable on every placeholder.
     */
    val PALETTE: List<Int> = listOf(
        0xFF8E3B46.toInt(), // wine
        0xFF9A4A1F.toInt(), // rust
        0xFF6B5A12.toInt(), // olive gold
        0xFF3F6B2A.toInt(), // moss
        0xFF1F6B5A.toInt(), // teal
        0xFF1F5F7A.toInt(), // sea blue
        0xFF34508F.toInt(), // indigo
        0xFF5B45A0.toInt(), // violet
        0xFF853F86.toInt(), // plum
        0xFF5C5650.toInt(), // warm grey
    )

    const val TEXT_COLOR: Int = 0xFFFFFFFF.toInt()

    /** The placeholder background for [bookId], the same on every device and run. */
    fun coverColor(bookId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray(Charsets.UTF_8))
        val n = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
        return PALETTE[n % PALETTE.size]
    }

    /** WCAG contrast ratio of two opaque ARGB colours (used by the tests to check [PALETTE]). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }
}
