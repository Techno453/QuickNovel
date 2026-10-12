package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.providers.ReaderHtml
import com.lagradost.quicknovel.providers.SourceGuard
import com.lagradost.quicknovel.providers.SourceGuard.Block
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the shared [SourceGuard] and [ReaderHtml] helpers used by the raw CJK providers. */
class SourceHelpersTest {
    private fun headers(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.associate { it.first.lowercase() to it.second }
        return { map[it.lowercase()] }
    }

    @Test
    fun cloudflareManagedChallenge() {
        val body = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>…</body></html>"
        assertEquals(
            Block.CLOUDFLARE_CHALLENGE,
            SourceGuard.classify(403, headers("cf-mitigated" to "challenge", "server" to "cloudflare"), body)
        )
        // Same page without the header still counts.
        assertEquals(Block.CLOUDFLARE_CHALLENGE, SourceGuard.classify(403, headers("server" to "cloudflare"), body))
    }

    @Test
    fun cloudflareCountryBan() {
        val body = "<html><body><span class=\"cf-error-code\">1009</span> has banned the country or region</body></html>"
        assertEquals(Block.CLOUDFLARE_BANNED, SourceGuard.classify(403, headers("server" to "cloudflare"), body))
    }

    @Test
    fun rateLimitCaptchaAndPlainForbidden() {
        assertEquals(Block.RATE_LIMITED, SourceGuard.classify(429, headers(), ""))
        assertEquals(Block.CAPTCHA, SourceGuard.classify(403, headers(), "<div class=\"g-recaptcha\"></div>"))
        assertEquals(Block.FORBIDDEN, SourceGuard.classify(403, headers("server" to "nginx"), "<h1>403 Forbidden</h1>"))
    }

    @Test
    fun healthyPagesPass() {
        val withPassiveScript = "<html><body>" + "正文".repeat(20) +
                "<script>a.src='/cdn-cgi/challenge-platform/scripts/jsd/main.js'</script></body></html>"
        assertNull(SourceGuard.classify(200, headers("server" to "cloudflare", "cf-ray" to "x"), withPassiveScript))
        assertNull(SourceGuard.classify(404, headers(), "not found"))
        // A 503 from a non-Cloudflare origin is an outage, not a challenge.
        assertNull(SourceGuard.classify(503, headers("server" to "nginx"), "Service Unavailable"))
    }

    @Test
    fun messagesNameTheSite() {
        Block.values().forEach { assertTrue(SourceGuard.message("Site X", it).startsWith("Site X")) }
    }

    @Test
    fun readerHtmlSplitsBreaksBlocksAndNewlines() {
        val el = Jsoup.parseBodyFragment(
            "<div>　一行目<br>二行目<p>三行目\n四行目</p><img data-src=\"//img.example/a.png\" src=\"data:,\">" +
                    "<script>x()</script>&nbsp;</div>",
            "https://example.com/"
        ).body()
        assertEquals(
            "<p>一行目</p>\n\n<p>二行目</p>\n\n<p>三行目 四行目</p>\n\n<p><img src=\"https://img.example/a.png\"></p>",
            ReaderHtml.fromElement(el)
        )
        assertEquals(listOf("一行目", "二行目", "三行目", "四行目"), ReaderHtml.lines(el, newlinesAreBreaks = true))
        assertNull(ReaderHtml.fromLines(listOf(" ", "　")))
        assertEquals("<p>a &lt; b</p>", ReaderHtml.fromLines(listOf("a < b")))
    }
}
