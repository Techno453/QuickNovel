package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Records one page into `src/test/resources/providers/` through the app's own OkHttp stack
 * (some sites, e.g. 69shuba behind Cloudflare, refuse curl but serve OkHttp). GBK pages are
 * decoded and saved as UTF-8. Skipped unless `-Dlive.record=<url>` is given:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*RecordFixture*' \
 *       -Dlive.record=https://www.69shuba.com/book/90435.htm -Dlive.record.out=69shuba/info.html
 *
 * Optional: `-Dlive.record.post=<form body>` sends a POST (body encoded as ISO-8859-1, so
 * pre-encode GBK forms), `-Dlive.record.referer=<url>`. The page is saved as-is: scrub chapter
 * prose with app/src/test/tools/scrub_prose.py before committing (see the README there).
 */
class RecordFixture {
    @Test
    fun record() = runBlocking {
        val url = System.getProperty("live.record")
        assumeTrue("run with -Dlive.record=<url> -Dlive.record.out=<provider>/<name>", !url.isNullOrBlank())
        val out = System.getProperty("live.record.out") ?: error("-Dlive.record.out=<provider>/<name> is required")
        CjkProvidersLiveSmokeTest.initHttp(force = true)

        val headers = buildMap {
            put("User-Agent", BROWSER_UA)
            put("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8,ko;q=0.7,en;q=0.6")
            System.getProperty("live.record.referer")?.let { put("Referer", it) }
        }
        val post = System.getProperty("live.record.post")
        val response = if (post == null) {
            MainActivity.app.get(url!!, headers = headers)
        } else {
            MainActivity.app.post(
                url!!, headers = headers,
                requestBody = post.toByteArray(Charsets.ISO_8859_1)
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            )
        }
        val html = ChineseProviderUtils.decodeHtml(
            response.okhttpResponse.body.bytes(),
            response.okhttpResponse.header("Content-Type")
        )
        val file = File("src/test/resources/providers", out)
        file.parentFile?.mkdirs()
        file.writeText(html, Charsets.UTF_8)
        println("[record] $url -> HTTP ${response.code}, ${html.length} chars -> ${file.path}")
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
