package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.util.Apis
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fork reads raw CJK sources and translates them itself, so machine-translation (MTL)
 * aggregators are not registered (docs/sources.md, "Removed sources").
 */
class RemovedSourcesTest {
    private val mtlHosts = listOf(
        "lnmtl.com", "mtlnovel", "sonicmtl.com", "wtr-lab.com", "wuxiabox.com", "fanmtl.com", "wuxia.click",
        "ranovel.com", "meionovels.com", "novelbuddy", "nobadnovel.com", "pawread.com",
    )

    /** Romance-focused English sites dropped on the user's decision (docs/sources.md, "Removed sources"). */
    private val romanceHosts = listOf(
        "chrysanthemumgarden.com", "hiraethtranslation.com", "readonlinefreebook.com", "lunoxscans.com", "wattpad.com",
    )

    /**
     * Sources in languages other than Chinese, Japanese, Korean and English, dropped on the user's
     * decision (docs/sources.md, "Removed sources").
     */
    private val otherLanguageHosts = listOf(
        "ar-no.com", "devilnovels.com", "galaxynovels.com", "indowebnovel.id", "sakuranovel.id", "kolnovel.com",
        "novelasligeras.net", "novelmania.com.br", "novelfrance.fr", "ranobehub.org", "rewayat.club",
        "skynovels.net", "planetaepub.com",
    )

    @Test
    fun otherLanguageSitesAreNotRegistered() {
        val registered = Apis.apis.filter { api -> otherLanguageHosts.any { it in api.mainUrl } }
        assertTrue("Non-CJK, non-English sites registered: ${registered.map { it.name }}", registered.isEmpty())
    }

    @Test
    fun romanceFocusedSitesAreNotRegistered() {
        val registered = Apis.apis.filter { api -> romanceHosts.any { it in api.mainUrl } }
        assertTrue("Romance-focused sites registered: ${registered.map { it.name }}", registered.isEmpty())
    }

    @Test
    fun mtlAggregatorsAreNotRegistered() {
        val registered = Apis.apis.filter { api -> mtlHosts.any { it in api.mainUrl } }
        assertTrue("MTL aggregators registered: ${registered.map { it.name }}", registered.isEmpty())
    }
}
