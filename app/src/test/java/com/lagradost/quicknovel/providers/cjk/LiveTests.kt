package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.StreamResponse
import org.jsoup.Jsoup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Gate and smoke routine for the live provider tests in this package. They hit the real sites,
 * so they only run with `-Dlive=1` (or `LIVE=1`):
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*providers.cjk*' -Dlive=1
 */
object LiveTests {
    val enabled: Boolean
        get() = (System.getProperty("live") ?: System.getenv("LIVE"))?.trim() in setOf("1", "true", "yes")

    /** search → load first hit with chapters → loadHtml of its first chapter. */
    suspend fun smoke(api: MainAPI, query: String, chapterIndex: Int = 0): String {
        val results = api.search(query).orEmpty()
        println("[live] ${api.name}: search '$query' → ${results.size} results")
        assertTrue("${api.name}: no search results for '$query'", results.isNotEmpty())

        var loaded: StreamResponse? = null
        for (result in results.take(5)) {
            val response = api.load(result.url) as? StreamResponse ?: continue
            println("[live] ${api.name}: load ${result.url} → '${response.name}', ${response.data.size} chapters")
            if (response.data.size > chapterIndex) {
                loaded = response
                break
            }
        }
        assertNotNull("${api.name}: none of the first results had readable chapters", loaded)
        val chapter = loaded!!.data[chapterIndex]
        val html = api.loadHtml(chapter.url)
        assertNotNull("${api.name}: chapter ${chapter.url} returned null", html)
        val paragraphs = Jsoup.parseBodyFragment(html!!).select("p").map { it.text() }.filter { it.isNotBlank() }
        println("[live] ${api.name}: chapter '${chapter.name}' → ${paragraphs.size} paragraphs, ${html.length} chars")
        assertTrue("${api.name}: chapter text too short", paragraphs.size >= 3)

        if (api.hasMainPage) {
            val main = api.loadMainPage(
                1,
                api.mainCategories.firstOrNull()?.second,
                api.orderBys.firstOrNull()?.second,
                api.tags.firstOrNull()?.second,
            )
            println("[live] ${api.name}: main page ${main.url} → ${main.list.size} entries")
            assertTrue("${api.name}: empty main page", main.list.isNotEmpty())
        }
        return html
    }
}
