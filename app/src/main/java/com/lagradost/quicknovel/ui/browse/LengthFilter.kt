package com.lagradost.quicknovel.ui.browse

import com.lagradost.quicknovel.LengthRange
import com.lagradost.quicknovel.LengthUnit
import com.lagradost.quicknovel.NovelLength
import java.util.Locale

/**
 * Length filter of a source's browse page (docs/browse.md, "Length filter"). Pure, JVM-tested in
 * `LengthFilterTest`.
 *
 * - A source declares a [LengthUnit] (`MainAPI.lengthFilter`): pages (Royal Road), characters
 *   (Syosetu, Kakuyomu, Pixiv, Hameln, Chinese sites that list 字数) or chapters.
 * - A range is an inclusive [LengthRange] in that unit. Sources that filter server-side get it in
 *   `loadMainPage(page, mainCategory, orderBy, tag, length)`; for the others the app drops items
 *   outside the range ([matches]) and scans further pages ([scan]).
 * - Lengths come from the listing ([NovelLength]); every source falls back to the number in the
 *   latest chapter's name ([fromLatestChapter]), an approximate chapter count.
 */
object LengthFilter {
    /** A client-side load stops once this many items matched... */
    const val MIN_ITEMS_PER_LOAD = 10

    /** ...or after this many pages beyond the first were scanned. */
    const val MAX_EXTRA_PAGES = 3

    /** Least pause before an extra page that is not cached (the provider's rate limit applies when longer). */
    const val EXTRA_PAGE_DELAY_MS = 300L

    /** 万: characters are shown and entered in units of 10,000 (10万字 = 100,000 characters). */
    const val WAN = 10_000L

    // ---- presets ----

    enum class PresetId { SHORT, MEDIUM, LONG, EPIC }

    data class Preset(val id: PresetId, val range: LengthRange)

    /** Short / Medium / Long / Epic buckets of [unit]; neighbouring buckets share their edge. */
    fun presets(unit: LengthUnit): List<Preset> {
        val (a, b, c) = when (unit) {
            LengthUnit.PAGES -> Triple(100L, 500L, 1_500L)
            LengthUnit.CHARACTERS -> Triple(10 * WAN, 50 * WAN, 200 * WAN)
            LengthUnit.CHAPTERS -> Triple(50L, 200L, 1_000L)
        }
        return listOf(
            Preset(PresetId.SHORT, LengthRange(null, a)),
            Preset(PresetId.MEDIUM, LengthRange(a, b)),
            Preset(PresetId.LONG, LengthRange(b, c)),
            Preset(PresetId.EPIC, LengthRange(c, null)),
        )
    }

    /** The preset [range] is, if any. */
    fun presetOf(unit: LengthUnit, range: LengthRange?): PresetId? =
        presets(unit).firstOrNull { it.range == range }?.id

    // ---- range rules ----

    /**
     * [range] made canonical: non-positive ends are open (0 means "no minimum"), swapped ends are
     * put in order, and a range open at both ends is no filter (null).
     */
    fun normalize(range: LengthRange?): LengthRange? {
        if (range == null) return null
        val min = range.min?.takeIf { it > 0 }
        val max = range.max?.takeIf { it > 0 }
        if (min == null && max == null) return null
        if (min != null && max != null && min > max) return LengthRange(max, min)
        return LengthRange(min, max)
    }

    /** Whether [length] is inside [range] in [unit]; an item without a count in [unit] never is. */
    fun matches(length: NovelLength?, unit: LengthUnit, range: LengthRange?): Boolean {
        if (range == null) return true
        val value = length?.valueIn(unit) ?: return false
        if (range.min != null && value < range.min) return false
        if (range.max != null && value > range.max) return false
        return true
    }

    /**
     * Part of a Browse cache key for [range]: empty without a range (keys stay what they were
     * before the length filter), else `|len=min-max` with an open end left empty.
     */
    fun keyPart(range: LengthRange?): String =
        if (range == null) "" else "|len=${encode(range)}"

    /** "500-1500", "500-" or "-1500" (stored per source next to the remembered listing). */
    fun encode(range: LengthRange): String = "${range.min ?: ""}-${range.max ?: ""}"

    /** Reads [encode]; null when absent, malformed or open at both ends. */
    fun decode(raw: String?): LengthRange? {
        val text = raw?.trim() ?: return null
        val dash = text.indexOf('-')
        if (dash < 0) return null
        val min = text.substring(0, dash).trim().let { if (it.isEmpty()) null else it.toLongOrNull() ?: return null }
        val max = text.substring(dash + 1).trim().let { if (it.isEmpty()) null else it.toLongOrNull() ?: return null }
        return normalize(LengthRange(min, max))
    }

    // ---- text ----

    /** How the length chip (and a preset chip) reads; the values are already formatted. */
    sealed class Summary {
        object Any : Summary()
        data class Between(val min: String, val max: String) : Summary()
        data class AtLeast(val min: String) : Summary()
        data class AtMost(val max: String) : Summary()
    }

    fun summary(unit: LengthUnit, range: LengthRange?): Summary {
        val r = normalize(range) ?: return Summary.Any
        return when {
            r.min != null && r.max != null -> Summary.Between(formatValue(unit, r.min), formatValue(unit, r.max))
            r.min != null -> Summary.AtLeast(formatValue(unit, r.min))
            else -> Summary.AtMost(formatValue(unit, r.max!!))
        }
    }

    /** A range end: "1,500" (pages, chapters) or "50万" (characters; under 1万 "5,000"). */
    fun formatValue(unit: LengthUnit, value: Long): String =
        if (unit == LengthUnit.CHARACTERS && value >= WAN) formatWan(value) + "万" else formatCount(value)

    /** "1,234". */
    fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    /** Characters in 万 without the 万 sign: 2,830,123 → "283", 123,456 → "12.3", 1,000,000 → "100". */
    fun formatWan(characters: Long): String {
        val wan = characters.toDouble() / WAN
        return if (wan >= 100) formatCount(Math.round(wan)) else {
            val tenths = Math.round(wan * 10)
            if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.US, "%.1f", tenths / 10.0)
        }
    }

    /** Text of a custom min / max field for [value] (characters in 万). */
    fun inputText(unit: LengthUnit, value: Long?): String = when {
        value == null -> ""
        unit == LengthUnit.CHARACTERS -> formatWan(value).replace(",", "")
        else -> value.toString()
    }

    /** A custom min / max field read back: digits (and for characters a decimal, in 万); blank or invalid = open. */
    fun parseInput(unit: LengthUnit, text: String): Long? {
        val clean = normalizeDigits(text).replace(",", "").replace("，", "").trim()
        if (clean.isEmpty()) return null
        return if (unit == LengthUnit.CHARACTERS) {
            clean.toDoubleOrNull()?.takeIf { it > 0 }?.let { Math.round(it * WAN) }
        } else {
            clean.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    /** Templates of the card's length line, from string resources ("%1$s pages", "%1$s ch", ...). */
    data class CardLabels(
        val pages: String,
        val chapters: String,
        val chaptersApprox: String,
        val characters: String,
        val charactersApprox: String,
        val separator: String = " · ",
    )

    /**
     * The secondary line of a result card: pages, characters (in 万字) and chapters, whichever the
     * listing gave, e.g. "2,932 pages · 109 ch", "123万字 · 300 ch", "≈1,417 ch"; null without a length.
     */
    fun cardText(length: NovelLength?, labels: CardLabels): String? {
        if (length == null) return null
        val parts = buildList {
            length.pages?.let { add(labels.pages.format(formatCount(it))) }
            length.characters?.let {
                val value = if (it >= WAN) formatWan(it) + "万" else formatCount(it)
                add((if (length.approximate) labels.charactersApprox else labels.characters).format(value))
            }
            length.chapters?.let {
                add((if (length.approximate) labels.chaptersApprox else labels.chapters).format(formatCount(it)))
            }
        }
        return parts.joinToString(labels.separator).ifEmpty { null }
    }

    // ---- parsing ----

    private val countRegex = Regex("""(\d[\d,，]*(?:\.\d+)?)\s*([万萬千億亿]|[kKmM](?![A-Za-z]))?""")

    /** Full-width digits to ASCII. */
    fun normalizeDigits(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in '０'..'９') ('0' + (c - '０')) else c)
    }

    /**
     * The first count in a listing's text: "1,234 Pages" → 1234, "123.4万字" → 1,234,000,
     * "全123部分" → 123, "123화" → 123, "5.45M" → 5,450,000, "1.2K" → 1200. Null without a number.
     */
    fun parseCount(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val m = countRegex.find(normalizeDigits(text)) ?: return null
        val number = m.groupValues[1].replace(",", "").replace("，", "").toDoubleOrNull() ?: return null
        val factor = when (m.groupValues[2]) {
            "万", "萬" -> 10_000.0
            "億", "亿" -> 100_000_000.0
            "千", "k", "K" -> 1_000.0
            "m", "M" -> 1_000_000.0
            else -> 1.0
        }
        return Math.round(number * factor)
    }

    private const val CN_NUM = "零〇一二两兩三四五六七八九十百千万萬"
    private val chineseChapterRegex =
        Regex("""第\s*([0-9,]+|[$CN_NUM]+)\s*[章回话話节節集篇页頁]""")
    private val englishChapterRegex =
        Regex("""(?i)\b(?:chapter|chap|ch|episode|ep|part)\.?\s*#?\s*(\d[\d,]*)""")
    private val suffixChapterRegex =
        Regex("""(?i)(\d[\d,]*)\s*(?:화|회|話|话|章|部分|エピソード|chapters?\b|episodes?\b|eps?\b)""")
    private val charactersRegex = Regex("""\d\s*[万萬]?\s*(?:文)?字""")
    private val dateRegex = Regex("""^\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}""")
    private val leadingRegex = Regex("""^\s*(\d{1,6})(?:\s*[.:：、．\-–)]|\s+)""")
    private val trailingRegex = Regex("""(\d{1,6})\s*$""")

    /** Largest chapter number taken as a count; anything above is a misread (an id, a date). */
    const val MAX_CHAPTER_NUMBER = 200_000L

    /**
     * The chapter number in a latest-chapter name: "Chapter 523", "Ch. 523", "Episode 12",
     * "EP.138", "第523章", "第一千零五章", "123화", "全123部分", "109 Chapters", a leading "1417." or
     * a trailing number. Null when there is none, or when the text is a character count ("289.4万字").
     */
    fun chapterNumber(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val t = normalizeDigits(text).trim()
        val chinese = chineseChapterRegex.find(t)?.groupValues?.get(1)
        val number = if (chinese != null) {
            chinese.replace(",", "").toLongOrNull() ?: chineseNumber(chinese)
        } else {
            if (charactersRegex.containsMatchIn(t) || dateRegex.containsMatchIn(t)) return null
            val digits = englishChapterRegex.find(t)?.groupValues?.get(1)
                ?: suffixChapterRegex.find(t)?.groupValues?.get(1)
                ?: leadingRegex.find(t)?.groupValues?.get(1)
                ?: trailingRegex.find(t)?.groupValues?.get(1)
                ?: return null
            digits.replace(",", "").toLongOrNull()
        }
        return number?.takeIf { it in 1..MAX_CHAPTER_NUMBER }
    }

    /** Approximate chapters from a latest-chapter name ([chapterNumber]), or null. */
    fun fromLatestChapter(latestChapter: String?): NovelLength? =
        chapterNumber(latestChapter)?.let { NovelLength(chapters = it, approximate = true) }

    /** A Chinese numeral: "一千零五" → 1005, "十二" → 12, "两百" → 200, "三万五千" → 35000. */
    fun chineseNumber(text: String): Long? {
        var total = 0L
        var section = 0L
        var digit = -1L
        var any = false
        for (c in text) {
            val d = "零〇一二三四五六七八九".indexOf(c).let { i ->
                when {
                    i == 0 || i == 1 -> 0L
                    i > 1 -> (i - 1).toLong()
                    c == '两' || c == '兩' -> 2L
                    else -> -1L
                }
            }
            if (d >= 0) {
                digit = d; any = true; continue
            }
            val unit = when (c) {
                '十' -> 10L
                '百' -> 100L
                '千' -> 1_000L
                '万', '萬' -> 10_000L
                else -> return null
            }
            any = true
            if (unit == 10_000L) {
                total += (section + digit.coerceAtLeast(0)) * unit
                section = 0
            } else {
                section += (if (digit < 0) 1 else digit) * unit
            }
            digit = -1
        }
        if (!any) return null
        return total + section + digit.coerceAtLeast(0)
    }

    // ---- client-side paging ----

    /**
     * One client-side load: the matching [items], the last page scanned, whether the list ended,
     * and the URLs of every new item scanned (matching or not; the next load's `known`).
     */
    data class Scan<T>(
        val items: List<T>,
        val lastPage: Int,
        val end: Boolean,
        val url: String,
        val scannedUrls: List<String> = emptyList(),
    )

    /**
     * Loads pages from [startPage] on and keeps the items [keep] accepts, until [minItems] matched,
     * [maxPages] pages were scanned, or a page adds nothing new (the end of the list: sites repeat
     * their last page or return nothing). [known] are URLs scanned by earlier loads; [pause] runs
     * before every page after the first. A failure of the first page fails the load; a later
     * failure ends it with what matched so far (or fails it when nothing matched).
     */
    suspend fun <T> scan(
        startPage: Int,
        known: Set<String>,
        urlOf: (T) -> String,
        keep: (T) -> Boolean,
        minItems: Int = MIN_ITEMS_PER_LOAD,
        maxPages: Int = 1 + MAX_EXTRA_PAGES,
        pause: suspend (page: Int) -> Unit = {},
        load: suspend (page: Int) -> Result<Pair<String, List<T>>>,
    ): Result<Scan<T>> {
        val seen = HashSet(known)
        val kept = ArrayList<T>()
        val scanned = ArrayList<String>()
        var url = ""
        var page = startPage
        var lastPage = startPage - 1
        var end = false
        while (true) {
            if (page > startPage) pause(page)
            val result = load(page)
            val (pageUrl, items) = result.getOrElse { error ->
                if (kept.isEmpty()) return Result.failure(error)
                return Result.success(Scan(kept, lastPage, false, url, scanned))
            }
            if (url.isEmpty()) url = pageUrl
            lastPage = page
            val fresh = items.filter { seen.add(urlOf(it)) }
            if (fresh.isEmpty()) {
                end = true
                break
            }
            scanned += fresh.map(urlOf)
            kept += fresh.filter(keep)
            if (kept.size >= minItems || page - startPage + 1 >= maxPages) break
            page++
        }
        return Result.success(Scan(kept, lastPage, end, url, scanned))
    }
}
