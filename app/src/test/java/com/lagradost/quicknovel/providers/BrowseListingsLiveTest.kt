package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Live check of the browse listings (main page) of 69shuba, Quanben and Syosetu: every listing's
 * first page is non-empty and, where the site pages it, page 2 is non-empty and brings new
 * works. Skipped unless run with `-Dlive=1` (or `LIVE=1`):
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*BrowseListingsLiveTest*' -Dlive=1
 */
@RunWith(Parameterized::class)
class BrowseListingsLiveTest(private val case: Case) {

    /** One listing: [mainCategory], [orderBy], [tag] as passed to `loadMainPage`. */
    class Case(
        val api: MainAPI,
        val label: String,
        val mainCategory: String?,
        val orderBy: String?,
        val tag: String?,
        val paged: Boolean = true,
    ) {
        override fun toString(): String = "${api.name}: $label"
    }

    companion object {
        private val enabled: Boolean
            get() = (System.getProperty("live") ?: System.getenv("LIVE"))
                ?.let { it.isNotBlank() && it != "0" && !it.equals("false", true) } == true

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> {
            val shuba = BiqugeProvider()
            val quanben = QuanbenProvider()
            val syosetu = SyosetuProvider()
            fun MainAPI.default(label: String, main: String? = mainCategories.firstOrNull()?.second,
                                order: String? = orderBys.firstOrNull()?.second, tag: String? = tags.firstOrNull()?.second,
                                paged: Boolean = true) = Case(this, label, main, order, tag, paged)
            return listOf(
                shuba.default("default (popular, monthly)"),
                shuba.default("popular, weekly", main = "weekvisit|0"),
                shuba.default("recommended", main = "allvote|0"),
                shuba.default("latest updates", main = BiqugeProvider.LATEST, paged = false),
                shuba.default("completed", main = "monthvisit|1"),
                shuba.default("ongoing", main = "monthvisit|2"),
                shuba.default("romance genre", tag = "3"),

                quanben.default("default (recommended)", paged = false),
                quanben.default("xuanhuan genre", tag = "xuanhuan"),
                quanben.default("urban genre", tag = "dushi"),

                syosetu.default("default (all genres, all-time)"),
                syosetu.default("daily", order = "daily"),
                syosetu.default("weekly", order = "weekly"),
                syosetu.default("monthly", order = "monthly"),
                syosetu.default("quarterly", order = "quarter"),
                syosetu.default("yearly", order = "yearly"),
                syosetu.default("latest updates", order = SyosetuProvider.LATEST),
                syosetu.default("completed", tag = "er"),
                syosetu.default("ongoing, daily", order = "daily", tag = "r"),
                syosetu.default("high fantasy, weekly", main = "201", order = "weekly"),
                syosetu.default("isekai fantasy, completed", main = "i2", tag = "er"),
                syosetu.default("latest updates, high fantasy, completed", main = "201", order = SyosetuProvider.LATEST, tag = "er"),
            )
        }

        @BeforeClass
        @JvmStatic
        fun initHttp() = CjkProvidersLiveSmokeTest.initHttp(force = false)
    }

    @Test
    fun firstPageNonEmptyAndPage2Differs() = runBlocking {
        assumeTrue("live test: run with -Dlive=1 (or LIVE=1)", enabled)
        val api = case.api
        val page1 = api.loadMainPage(1, case.mainCategory, case.orderBy, case.tag)
        println("[live browse] $case: page 1 ${page1.url} -> ${page1.list.size} entries, first '${page1.list.firstOrNull()?.name}'")
        assertTrue("$case: empty first page ${page1.url}", page1.list.isNotEmpty())
        assertTrue("$case: entry without name/url", page1.list.all { it.name.isNotBlank() && it.url.startsWith("http") })

        delay(500)
        val page2 = api.loadMainPage(2, case.mainCategory, case.orderBy, case.tag)
        val urls1 = page1.list.map { it.url }.toSet()
        val fresh = page2.list.count { it.url !in urls1 }
        println("[live browse] $case: page 2 ${page2.url} -> ${page2.list.size} entries, $fresh not on page 1")
        if (case.paged) {
            assertTrue("$case: empty page 2 ${page2.url}", page2.list.isNotEmpty())
            assertTrue("$case: page 2 repeats page 1", fresh > page2.list.size / 2)
        } else {
            // Single-page listings return nothing for page 2 instead of repeating page 1.
            assertTrue("$case: single-page listing repeated on page 2", page2.list.isEmpty())
        }
        delay(500)
    }
}
