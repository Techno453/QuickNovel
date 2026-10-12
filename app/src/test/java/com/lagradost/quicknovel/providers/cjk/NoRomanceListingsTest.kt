package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.util.Apis
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Romance, BL / danmei, GL / yuri, "no romance" (无CP) and sexual-orientation listings are
 * deliberately not offered in Browse (docs/browse.md), and romance-focused sources are not
 * registered. Scans every registered CJK provider's browse axes so none creeps back.
 */
class NoRomanceListingsTest {
    private val cjk = setOf("zh", "ja", "ko")

    /** Matched anywhere in a label (CJK words) or case-insensitively (Latin words). */
    private val labelWords = listOf(
        "言情", "纯爱", "純愛", "耽美", "百合", "无CP", "無CP", "多元", "现言", "古言", "女性向", "女生同人",
        "恋愛", "ラブ", "悪役令嬢", "로맨스", "로판",
    )
    private val latinWords = Regex("""romance|danmei|yuri|boy'?s?\s*love|girl'?s?\s*love|\bBL\b|\bGL\b""", RegexOption.IGNORE_CASE)

    /** Site slugs of romance categories that once were listing values. */
    private val valueWords = Regex("""yanqing|danmei|baihe|xianyan|love_story|romance""", RegexOption.IGNORE_CASE)

    @Test
    fun noCjkProviderOffersARomanceListing() {
        val providers = Apis.apis.filter { it.lang in cjk }
        assertTrue("expected the CJK providers to be registered", providers.size >= 10)
        val offenders = providers.flatMap { api ->
            (api.mainCategories + api.orderBys + api.tags)
                .filter { (label, value) ->
                    labelWords.any { it in label } || latinWords.containsMatchIn(label) ||
                            valueWords.containsMatchIn(value)
                }
                .map { "${api.name}: ${it.first} (${it.second})" }
        }
        assertTrue("romance listings offered: $offenders", offenders.isEmpty())
    }

    @Test
    fun romanceSourcesAreNotRegistered() {
        val removed = Apis.apis.filter { "jjwxc" in it.mainUrl || "晋江" in it.name }
        assertTrue("romance sources registered: ${removed.map { it.name }}", removed.isEmpty())
    }
}
