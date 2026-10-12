package com.lagradost.quicknovel.providers

import com.lagradost.nicehttp.NiceResponse
import com.lagradost.quicknovel.ErrorLoadingException

/**
 * Shared "is the site blocking us?" checks for the raw CJK sources, so that a challenge or ban
 * page surfaces as a readable error instead of an empty search or a chapter full of junk.
 *
 * Pure (no Android calls), so it is covered by JVM unit tests.
 *
 * Cloudflare note: sites that require a `cf_clearance` cookie are handled by the existing
 * [com.lagradost.quicknovel.network.CloudflareKiller] interceptor (enabled per provider with
 * `usesCloudFlareKiller = true`), which solves the challenge in a hidden WebView, caches the
 * cookie per host and retries. This object only *classifies* responses; it never solves anything.
 */
object SourceGuard {
    enum class Block {
        /** Cloudflare managed/JS challenge ("Just a moment...", `cf-mitigated: challenge`). */
        CLOUDFLARE_CHALLENGE,

        /** Cloudflare firewall/geo ban (error 1006/1009/1020): retrying will not help. */
        CLOUDFLARE_BANNED,

        /** HTTP 429 or an explicit "too many requests" page. */
        RATE_LIMITED,

        /** A reCAPTCHA / hCaptcha / Turnstile form instead of content. */
        CAPTCHA,

        /** Any other 401/403. */
        FORBIDDEN,
    }

    private val challengeMarkers = listOf(
        "<title>just a moment",
        "cf-browser-verification",
        "_cf_chl_opt",
        "cf-chl-",
        "checking your browser before accessing",
    )
    private val bannedMarkers = listOf(
        "error code: 1006", "error code: 1009", "error code: 1020",
        "cf-error-code\">1006", "cf-error-code\">1009", "cf-error-code\">1020",
        "has banned the country or region",
        "you have been blocked",
    )
    private val captchaMarkers = listOf("g-recaptcha", "h-captcha", "cf-turnstile")

    /**
     * Classifies a response. [header] looks up a response header case-insensitively; [body] may be
     * truncated (only the first few KB matter). Normal pages return null even when they embed
     * Cloudflare's passive bot-management script (`/cdn-cgi/challenge-platform/scripts/jsd/...`),
     * which many healthy sites (e.g. piaotia) include on every page.
     */
    fun classify(code: Int, header: (String) -> String?, body: String): Block? {
        val sample = body.take(BODY_SAMPLE).lowercase()
        val isCloudflare = header("cf-ray") != null ||
                header("server")?.contains("cloudflare", ignoreCase = true) == true
        if (header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) {
            return Block.CLOUDFLARE_CHALLENGE
        }
        if (code == 429) return Block.RATE_LIMITED
        if (code == 403 || code == 503 || code == 401) {
            if (bannedMarkers.any { sample.contains(it) }) return Block.CLOUDFLARE_BANNED
            if (challengeMarkers.any { sample.contains(it) }) return Block.CLOUDFLARE_CHALLENGE
            if (captchaMarkers.any { sample.contains(it) }) return Block.CAPTCHA
            if (code == 503) return if (isCloudflare) Block.CLOUDFLARE_CHALLENGE else null
            return Block.FORBIDDEN
        }
        if (code in 200..299 && sample.length < SHORT_PAGE &&
            challengeMarkers.any { sample.contains(it) }
        ) {
            return Block.CLOUDFLARE_CHALLENGE
        }
        return null
    }

    fun message(site: String, block: Block): String = when (block) {
        Block.CLOUDFLARE_CHALLENGE ->
            "$site is showing a Cloudflare browser check. Open the site once in the in-app browser (WebView) to pass it, then retry."

        Block.CLOUDFLARE_BANNED ->
            "$site refused the connection (Cloudflare firewall or region block). A different network or VPN is needed."

        Block.RATE_LIMITED ->
            "$site is rate limiting requests. Wait a minute and retry, or download fewer chapters at once."

        Block.CAPTCHA ->
            "$site asked for a CAPTCHA. Open the page in the in-app browser to solve it, then retry."

        Block.FORBIDDEN ->
            "$site refused access (HTTP 403). The work may be login-only, adult-gated or removed."
    }

    /** Throws [ErrorLoadingException] with a readable message if [response] is a block page. */
    fun check(site: String, response: NiceResponse, body: String = response.text) {
        val block = classify(response.code, { response.headers[it] }, body) ?: return
        throw ErrorLoadingException(message(site, block))
    }

    /** Throws for non-block HTTP errors too (404 for a removed work, 5xx outages). */
    fun requireOk(site: String, response: NiceResponse, body: String = response.text) {
        check(site, response, body)
        if (response.code >= 400) {
            throw ErrorLoadingException("$site returned HTTP ${response.code} for ${response.url}")
        }
    }

    private const val BODY_SAMPLE = 16 * 1024
    private const val SHORT_PAGE = 12 * 1024
}
