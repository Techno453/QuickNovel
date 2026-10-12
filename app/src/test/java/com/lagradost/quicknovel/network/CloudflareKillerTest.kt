package com.lagradost.quicknovel.network

import com.lagradost.quicknovel.network.CloudflareKiller.Companion.isCloudflareChallenge
import com.lagradost.quicknovel.providers.ProviderFixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CloudflareKiller.isCloudflareChallenge] against recorded pages:
 * - `cloudflare/challenge_69shuba.html`: the managed challenge 69shuba answered for
 *   /novels/newhot_0_0_1.htm on 2026-10-11 (HTTP 403, `cf-mitigated: challenge`).
 * - `piaotia/chapter.html`: a normal piaotia chapter (HTTP 200 behind Cloudflare) that embeds
 *   the passive `/cdn-cgi/challenge-platform/scripts/jsd/main.js` script in its first 10 KB.
 *   The old check (`/cdn-cgi/` anywhere in the sample) took it for a challenge, so every
 *   request to such a site would have started a WebView solve.
 */
class CloudflareKillerTest {
    private val challenge = ProviderFixtures.bytes("cloudflare/challenge_69shuba.html").toString(Charsets.UTF_8)

    // GBK on disk; the check only looks at ASCII markup, so ISO-8859-1 keeps it byte for byte.
    private val piaotiaChapter = ProviderFixtures.bytes("piaotia/chapter.html").toString(Charsets.ISO_8859_1)

    private val cloudflareHeaders = mapOf("server" to "cloudflare", "cf-ray" to "a492f5f329ba79a0-DEN")
    private val challengeHeaders = cloudflareHeaders + ("cf-mitigated" to "challenge")

    private fun headers(map: Map<String, String>): (String) -> String? =
        { name -> map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }

    private fun sample(body: String) = body.take(CloudflareKiller.BODY_SAMPLE_BYTES.toInt())

    @Test
    fun recordedSamplesAreWhatTheTestsAssume() {
        assertTrue(sample(piaotiaChapter).contains("/cdn-cgi/challenge-platform/scripts/jsd/main.js"))
        assertTrue(challenge.contains("<title>Just a moment...</title>"))
    }

    @Test
    fun managedChallengeIsDetected() {
        // As recorded: 403 with cf-mitigated.
        assertTrue(isCloudflareChallenge(403, headers(challengeHeaders), sample(challenge)))
        // The header alone is enough (Cloudflare's own signal).
        assertTrue(isCloudflareChallenge(403, headers(challengeHeaders), ""))
        // The markup alone is enough, on a block status or on a 200.
        assertTrue(isCloudflareChallenge(403, headers(cloudflareHeaders), sample(challenge)))
        assertTrue(isCloudflareChallenge(503, headers(emptyMap()), sample(challenge)))
        assertTrue(isCloudflareChallenge(200, headers(cloudflareHeaders), sample(challenge)))
    }

    @Test
    fun hamelnManagedChallengeIsDetected() {
        // syosetu.org (Hameln) answered every JVM request on 2026-10-12 with this page: HTTP 403,
        // cf-mitigated: challenge, cType 'managed' (a real browser passes it without a click).
        val hameln = ProviderFixtures.bytes("cloudflare/challenge_hameln.html").toString(Charsets.UTF_8)
        assertTrue(hameln.contains("<title>Just a moment...</title>") && hameln.contains("cType: 'managed'"))
        assertTrue(isCloudflareChallenge(403, headers(challengeHeaders), sample(hameln)))
        // Markup alone, without the header (e.g. a proxy that drops it).
        assertTrue(isCloudflareChallenge(403, headers(cloudflareHeaders), sample(hameln)))
        assertTrue(isCloudflareChallenge(200, headers(emptyMap()), sample(hameln)))
    }

    @Test
    fun normalPageWithCdnCgiScriptIsNotAChallenge() {
        assertFalse(isCloudflareChallenge(200, headers(cloudflareHeaders), sample(piaotiaChapter)))
        assertFalse(isCloudflareChallenge(200, headers(emptyMap()), sample(piaotiaChapter)))
        // A Hameln page as served once the challenge is cleared.
        val hamelnNovel = ProviderFixtures.bytes("hameln/novel.html").toString(Charsets.UTF_8)
        assertFalse(isCloudflareChallenge(200, headers(cloudflareHeaders), sample(hamelnNovel)))
        // Email obfuscation links and the decode script are /cdn-cgi/ too.
        val emailProtected = """<html><head><title>Book</title></head><body>
            <a href="/cdn-cgi/l/email-protection#1a2b">[email&#160;protected]</a>
            <script data-cfasync="false" src="/cdn-cgi/scripts/5c5dd728/cloudflare-static/email-decode.min.js"></script>
            </body></html>"""
        assertFalse(isCloudflareChallenge(200, headers(cloudflareHeaders), emailProtected))
    }

    @Test
    fun blockStatusWithoutChallengeMarkupIsNotSolvable() {
        // A plain 403/404 or a firewall ban (1020) from a Cloudflare-fronted site: a WebView
        // cannot get a cf_clearance for it, so no solve is started.
        assertFalse(isCloudflareChallenge(403, headers(cloudflareHeaders), sample(piaotiaChapter)))
        val banned = "<html><head><title>Access denied | example.com used Cloudflare to restrict access</title></head>" +
                "<body><span class=\"cf-error-code\">1020</span></body></html>"
        assertFalse(isCloudflareChallenge(403, headers(cloudflareHeaders), banned))
        assertFalse(isCloudflareChallenge(404, headers(cloudflareHeaders), "<html>Not found</html>"))
    }

    @Test
    fun proseMentioningJustAMomentIsNotAChallenge() {
        val chapter = "<html><head><title>Chapter 3</title></head><body><p>\"Just a moment,\" she said. " +
                "Checking your browser history was not an option.</p></body></html>"
        assertFalse(isCloudflareChallenge(200, headers(cloudflareHeaders), chapter))
    }

    @Test
    fun turnstileForms() {
        val turnstilePage = """<!DOCTYPE html><html><head><title>Verify</title>
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script></head>
            <body><form method="post"><div class="cf-turnstile" data-sitekey="0x4AAAA"></div></form></body></html>"""
        assertTrue(isCloudflareChallenge(403, headers(cloudflareHeaders), turnstilePage))
        // A short Cloudflare page that is nothing but a Turnstile form.
        assertTrue(isCloudflareChallenge(200, headers(cloudflareHeaders), turnstilePage))
        // A full page that only has a Turnstile widget somewhere (e.g. a login box) is content.
        val longPage = turnstilePage.replace("<body>", "<body>" + "<p>text</p>".repeat(2_000))
        assertFalse(isCloudflareChallenge(200, headers(cloudflareHeaders), sample(longPage)))
        // Not served by Cloudflare: not a Cloudflare challenge.
        assertFalse(isCloudflareChallenge(200, headers(emptyMap()), turnstilePage))
    }

    @Test
    fun redirectIntoCdnCgiIsAChallenge() {
        assertTrue(isCloudflareChallenge(302, headers(mapOf("Location" to "/cdn-cgi/challenge-platform/h/b/rc/abc")), ""))
        assertFalse(isCloudflareChallenge(302, headers(mapOf("Location" to "/book/1.htm")), ""))
    }
}
