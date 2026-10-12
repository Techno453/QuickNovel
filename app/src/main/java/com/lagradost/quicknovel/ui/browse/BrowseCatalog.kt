package com.lagradost.quicknovel.ui.browse

/**
 * Pure (JVM-tested) rules of the Browse tab: which sources are shown in which order, and which
 * listing of a source stands for "Popular", "New" and "Completed" (docs/browse.md).
 */
object BrowseCatalog {
    /** CJK languages, in the order their groups are shown. */
    val CJK_ORDER = listOf("zh", "ja", "ko")

    /** A source as Browse needs it (labels of `mainCategories` / `orderBys` / `tags`). */
    data class Source(
        val name: String,
        val lang: String,
        val mainCategories: List<String> = emptyList(),
        val orderBys: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
    )

    /** One section of the Browse home. [lang] null = "Other sources" (collapsed by default). */
    data class Group(val lang: String?, val sources: List<Source>) {
        val isOther: Boolean get() = lang == null
    }

    fun languageName(lang: String?): String = when (lang?.lowercase()?.substringBefore('-')) {
        "zh" -> "Chinese"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "en" -> "English"
        null -> "Other"
        else -> lang
    }

    private fun base(lang: String) = lang.lowercase().substringBefore('-').substringBefore('_')

    /**
     * Groups [sources] by language: the user's reading languages first (from the setup wizard, if
     * any), then the remaining CJK languages (zh, ja, ko), then everything else in one "Other
     * sources" group. Sources keep their given order inside a group.
     */
    fun group(sources: List<Source>, readingLanguages: List<String> = emptyList()): List<Group> {
        val order = (readingLanguages.map { base(it) } + CJK_ORDER).distinct()
        val byLang = sources.groupBy { base(it.lang) }
        val groups = order.mapNotNull { lang -> byLang[lang]?.let { Group(lang, it) } }
        val rest = sources.filter { base(it.lang) !in order }
        return if (rest.isEmpty()) groups else groups + Group(null, rest)
    }

    enum class ListingKind { POPULAR, NEW, COMPLETED }

    /** Indexes into a source's mainCategories / orderBys / tags (0 = the provider's default). */
    data class Listing(val category: Int = 0, val orderBy: Int = 0, val tag: Int = 0)

    private val completedRegex = Regex("""complet|finish|全本|完结|完結|완결""", RegexOption.IGNORE_CASE)
    private val newRegex = Regex("""\bnew(est)?\b""", RegexOption.IGNORE_CASE)
    private val latestRegex = Regex("""latest|recent|update""", RegexOption.IGNORE_CASE)

    /**
     * The listing of [source] closest to [kind], or null when the source has none (it is then
     * left out of a combined "New" / "Completed" feed). "Popular" is always the default listing
     * (index 0 everywhere): providers put their most popular ranking first.
     */
    fun resolve(source: Source, kind: ListingKind): Listing? {
        if (kind == ListingKind.POPULAR) return Listing()
        fun find(labels: List<String>): Int? = when (kind) {
            ListingKind.COMPLETED -> labels.indexOfFirst { completedRegex.containsMatchIn(it) }
            ListingKind.NEW -> labels.indexOfFirst { newRegex.containsMatchIn(it) && !completedRegex.containsMatchIn(it) }
                .takeIf { it >= 0 }
                ?: labels.indexOfFirst { latestRegex.containsMatchIn(it) && !completedRegex.containsMatchIn(it) }
            ListingKind.POPULAR -> 0
        }.takeIf { it >= 0 }
        find(source.mainCategories)?.let { return Listing(category = it) }
        find(source.orderBys)?.let { return Listing(orderBy = it) }
        return null
    }
}
