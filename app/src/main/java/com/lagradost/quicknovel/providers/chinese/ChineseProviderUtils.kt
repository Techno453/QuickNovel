package com.lagradost.quicknovel.providers.chinese

import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup

/**
 * Utility extensions for Chinese novel providers.
 */
object ChineseProviderUtils {

    /**
     * Detect charset from HTML meta tags or Content-Type header and decode bytes accordingly.
     * Handles GBK, GB2312, GB18030, and UTF-8 encoded Chinese novel sites.
     */
    fun NiceResponse.textWithEncoding(): String {
        val bytes = okhttpResponse.body.bytes()

        // Try to detect encoding from Content-Type header
        val contentType = okhttpResponse.header("Content-Type") ?: ""
        val headerCharset = extractCharset(contentType)
        if (headerCharset != null) {
            return bytes.toString(charset(headerCharset))
        }

        // Try UTF-8 first — detect from BOM or attempt parse
        val utf8Text = bytes.toString(Charsets.UTF_8)

        // Look for charset declaration in HTML meta tags
        val metaCharset = extractMetaCharset(utf8Text)
        if (metaCharset != null) {
            val lc = metaCharset.lowercase()
            return when {
                lc.contains("gbk") || lc.contains("gb2312") || lc.contains("gb18030") -> {
                    bytes.toString(charset("GBK"))
                }
                lc.contains("utf-8") || lc.contains("utf8") -> utf8Text
                else -> try {
                    bytes.toString(charset(metaCharset))
                } catch (e: Exception) {
                    utf8Text
                }
            }
        }

        return utf8Text
    }

    private fun extractCharset(contentType: String): String? {
        val match = Regex("""charset=([^\s;]+)""", RegexOption.IGNORE_CASE)
            .find(contentType) ?: return null
        return match.groupValues[1].trim('"', '\'')
    }

    private fun extractMetaCharset(html: String): String? {
        // <meta charset="UTF-8"> or <meta http-equiv="Content-Type" content="text/html; charset=GBK">
        val patterns = listOf(
            Regex("""<meta[^>]+charset=["']?([^"'\s;/>]+)""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["'][^"']*charset=([^"'\s;]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            return match.groupValues[1].trim()
        }
        return null
    }

    /**
     * Strip common ad patterns, navigation elements, and site-injected headers from chapter text.
     * Returns clean Chinese prose.
     */
    fun String.cleanChapterText(): String {
        var text = this

        // Remove common ad phrases (simplified/traditional Chinese)
        val adPatterns = listOf(
            // Common biquge-style noise
            Regex("""笔趣阁[^。\n]*"""),
            Regex("""biquge[^\n]*""", RegexOption.IGNORE_CASE),
            Regex("""www\.[^\s。\n]+""", RegexOption.IGNORE_CASE),
            Regex("""http[s]?://[^\s。\n]+""", RegexOption.IGNORE_CASE),
            Regex("""请记住.*?地址[^\n]*"""),
            Regex("""最新章节.*?下载[^\n]*"""),
            Regex("""手机版.*?阅读[^\n]*"""),
            Regex("""本书最新.*?[。\n]"""),
            Regex("""阅读.*?最快.*?[。\n]"""),
            Regex("""请.*?收藏.*?[。\n]"""),
            Regex("""天才一秒记住.*?[。\n]"""),
            Regex("""记住.*?网址.*?[。\n]"""),
            // Author notes / translator notes sometimes injected
            Regex("""【.*?】"""),
            Regex("""（求.*?）"""),
        )

        for (pattern in adPatterns) {
            text = text.replace(pattern, "")
        }

        // Normalize whitespace: collapse multiple blank lines
        text = text.replace(Regex("""\r\n|\r"""), "\n")
        text = text.replace(Regex("""\n{3,}"""), "\n\n")
        text = text.trim()

        return text
    }

    /**
     * Extract and clean a Chinese novel title string.
     */
    fun String.extractChineseTitle(): String {
        return this.replace(Regex("""_[^_]+$"""), "") // remove site suffix like "_笔趣阁"
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
