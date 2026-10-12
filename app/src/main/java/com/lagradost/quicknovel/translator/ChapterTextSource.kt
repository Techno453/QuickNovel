package com.lagradost.quicknovel.translator

/**
 * Where the translation pipeline reads a novel's chapter sources from. Online novels load a
 * chapter through their provider (`MainAPI.loadHtml`, with the Cloudflare retry); imported books
 * read it from the stored EPUB ([com.lagradost.quicknovel.imported.ImportedBooks]). Everything after
 * that (analysis, the per-chapter lock, source hash, Story Bible, engine, spend tags, storage) is
 * the same code for both.
 */
interface ChapterTextSource {
    /** Normalised source language (zh / ja / ko), or null when unknown / not translatable. */
    val lang: String?

    /** Number of chapters. */
    val size: Int

    /** Chapter [index]'s source HTML/text, or null if it cannot be loaded. */
    suspend fun load(index: Int): String?
}
