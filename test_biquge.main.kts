/**
 * Quick integration test for BiqugeProvider (69shuba.com).
 *
 * Run from repo root:
 *   test_biquge.bat
 *
 * Or manually:
 *   set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
 *   "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc" -script test_biquge.main.kts
 *
 * Tests: main page, search, load (novel page), loadHtml (chapter page)
 */

@file:DependsOn("org.jsoup:jsoup:1.21.2")

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

val MAIN_URL = "https://www.69shuba.com"
val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

// ─── HTTP helpers ──────────────────────────────────────────────────────────────

fun applyBrowserHeaders(conn: HttpURLConnection, referer: String? = null) {
    conn.setRequestProperty("User-Agent", UA)
    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    conn.setRequestProperty("Accept-Encoding", "identity") // disable gzip so we get raw bytes
    conn.setRequestProperty("Connection", "keep-alive")
    if (referer != null) conn.setRequestProperty("Referer", referer)
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 15_000
    conn.readTimeout = 20_000
}

fun get(url: String): Pair<String, ByteArray> {
    val conn = URL(url).openConnection() as HttpURLConnection
    applyBrowserHeaders(conn, MAIN_URL)
    val ct = conn.getHeaderField("Content-Type") ?: ""
    val bytes = conn.inputStream.readBytes()
    return ct to bytes
}

fun post(url: String, bodyStr: String): Pair<String, ByteArray> {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    applyBrowserHeaders(conn, MAIN_URL)
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    val bodyBytes = bodyStr.toByteArray(Charsets.ISO_8859_1)
    conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
    conn.outputStream.use { it.write(bodyBytes) }
    val ct = conn.getHeaderField("Content-Type") ?: ""
    val bytes = conn.inputStream.readBytes()
    return ct to bytes
}

// ─── Encoding detection ────────────────────────────────────────────────────────

fun decode(contentType: String, bytes: ByteArray): String {
    val ctCharset = Regex("""charset=([^\s;]+)""", RegexOption.IGNORE_CASE)
        .find(contentType)?.groupValues?.get(1)?.trim('"', '\'')
    if (ctCharset != null) return bytes.toString(charset(ctCharset))

    val utf8 = bytes.toString(Charsets.UTF_8)
    val meta = listOf(
        Regex("""<meta[^>]+charset=["']?([^"'\s;/>]+)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["'][^"']*charset=([^"'\s;]+)""", RegexOption.IGNORE_CASE)
    ).firstNotNullOfOrNull { it.find(utf8)?.groupValues?.get(1)?.trim() }

    return when {
        meta == null -> utf8
        meta.lowercase().run { contains("gbk") || contains("gb2312") || contains("gb18030") } ->
            bytes.toString(charset("GBK"))
        else -> utf8
    }
}

// ─── Selector helpers ──────────────────────────────────────────────────────────

fun Element.absHref(base: String): String = absUrl("href").ifBlank { "$base${attr("href")}" }
fun Element.coverSrc(base: String): String? {
    val img = selectFirst("img") ?: return null
    val src = img.attr("data-src").ifBlank { img.attr("src") }
    return if (src.startsWith("http")) src else "$base$src"
}

// ─── Tests ────────────────────────────────────────────────────────────────────

fun testMainPage() {
    println("\n" + "─".repeat(60))
    println("MAIN PAGE (latest updates, page 1)")
    println("─".repeat(60))
    val url = "$MAIN_URL/paihang/lastupdate/1/"
    val (ct, bytes) = get(url)
    val html = decode(ct, bytes)
    val doc = Jsoup.parse(html, url)

    val items = doc.select("ul.covers li, ul.cover-list li")
    println("Detected charset:  ${ct.substringAfter("charset=").ifBlank { "UTF-8 (fallback)" }}")
    println("Items matched:     ${items.size}  (selector: ul.covers li, ul.cover-list li)")

    if (items.isEmpty()) {
        println("⚠ No items — available <ul> classes: " +
            doc.select("ul[class]").map { "ul.${it.className()}" }.distinct().take(8))
    } else {
        items.take(5).forEach { el ->
            val linkEl = el.selectFirst("h3 > a[href], h4 > a[href]")
            println("  • ${linkEl?.text()?.trim() ?: "?title?"} → ${linkEl?.absHref(MAIN_URL) ?: "?"}")
        }
    }
}

fun testSearch(query: String): String? {
    println("\n" + "─".repeat(60))
    println("SEARCH  \"$query\"")
    println("─".repeat(60))
    val encoded = URLEncoder.encode(query, "GBK")
    val body = "searchkey=$encoded&submit=Search"
    println("POST body:  $body")

    val (ct, bytes) = post("$MAIN_URL/modules/article/search.php", body)
    val html = decode(ct, bytes)
    val doc = Jsoup.parse(html, MAIN_URL)
    println("Response title: ${doc.title()}")

    val items = doc.select("ul.covers li, ul.cover-list li")
    println("Items matched:  ${items.size}")

    if (items.isEmpty()) {
        println("⚠ No results — ul classes on page: " +
            doc.select("ul[class]").map { "ul.${it.className()}" }.distinct().take(8))
        println("Body snippet: ${doc.body().text().take(300)}")
        return null
    }

    var firstHref: String? = null
    items.take(5).forEach { el ->
        val linkEl = el.selectFirst("h3 > a[href], h4 > a[href]") ?: return@forEach
        val title = linkEl.text().trim()
        val href = linkEl.absHref(MAIN_URL)
        if (firstHref == null) firstHref = href
        println("  • $title → $href")
    }
    return firstHref
}

fun testLoad(url: String): String? {
    println("\n" + "─".repeat(60))
    println("LOAD NOVEL  $url")
    println("─".repeat(60))
    val (ct, bytes) = get(url)
    val html = decode(ct, bytes)
    val doc = Jsoup.parse(html, url)

    val title = doc.selectFirst("div.bookinfo h1, .booktitle, h1")?.text()?.trim()
    val author = doc.selectFirst(".booktag a, p.bookintr span, .author")?.text()?.trim()
    val cover = doc.selectFirst("div.bookimg img, .book-cover img, img.cover")
        ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
    val synopsis = doc.selectFirst("div.intro, div.desc, .bookintro")?.text()?.trim()

    println("Title:     $title")
    println("Author:    $author")
    println("Cover:     $cover")
    println("Synopsis:  ${synopsis?.take(120)}")

    val container = doc.selectFirst("div.listmain, #list, #chapterList, div.chapter-list")
    val chapters = container?.select("a[href]") ?: run {
        println("⚠ No chapter container — divs with id/class: " +
            doc.select("div[id], div[class]").map { "div#${it.id()}.${it.className()}" }.distinct().take(10))
        null
    }

    println("Chapters:  ${chapters?.size ?: 0}")
    if (chapters != null && chapters.isNotEmpty()) {
        val firstA = chapters.first()!!
        val lastA = chapters.last()!!
        println("First:     ${firstA.text()} → ${firstA.absHref(url)}")
        println("Last:      ${lastA.text()} → ${lastA.absHref(url)}")
        return firstA.absHref(url)
    }
    return null
}

fun testLoadHtml(url: String) {
    println("\n" + "─".repeat(60))
    println("LOAD CHAPTER  $url")
    println("─".repeat(60))
    val (ct, bytes) = get(url)
    val html = decode(ct, bytes)
    val doc = Jsoup.parse(html, url)

    val contentEl = listOf("div.txtnav", "div#content", "div.showtxt", "div#chaptercontent",
                            "div.read-content", "article.chapter-content")
        .firstNotNullOfOrNull { doc.selectFirst(it) }
        ?: doc.body().children().maxByOrNull { it.text().length }

    val matched = contentEl?.let { "${it.tagName()}${if(it.id().isNotBlank()) "#${it.id()}" else ""}.${it.className()}" }
    val text = contentEl?.text()?.trim() ?: ""

    println("Selector hit:  $matched")
    println("Text length:   ${text.length} chars")
    println()
    println("── First 400 chars ──")
    println(text.take(400))
    if (text.length < 100) println("⚠ Very short — check content selector")
}

// ─── Run ──────────────────────────────────────────────────────────────────────

println("=".repeat(60))
println("  BiqugeProvider integration test  →  69shuba.com")
println("=".repeat(60))
println()
println("NOTE: 69shuba.com geo-blocks non-Chinese IPs (returns 403).")
println("      Run this test on a device/emulator with a Chinese VPN,")
println("      or on the Android device itself via adb logcat.")
println()

// Verify search encoding is correct (no network needed)
println("─".repeat(60))
println("ENCODING CHECK (no network)")
println("─".repeat(60))
val testCases = mapOf(
    "斗破苍穹" to "%B6%B7%C6%C6%B2%D4%F1%B7",
    "修仙"     to "%D0%DE%CF%C9",
    "搜索"     to "%CB%D1%CB%F7"   // matches the screenshot you provided
)
var allOk = true
testCases.forEach { (input, expected) ->
    val actual = URLEncoder.encode(input, "GBK")
    val ok = actual.equals(expected, ignoreCase = true)
    println("  ${if (ok) "✓" else "✗"} encode(\"$input\") = $actual  ${if (ok) "" else "(expected $expected)"}")
    if (!ok) allOk = false
}
println(if (allOk) "  GBK encoding is correct." else "  ⚠ GBK encoding mismatch!")

println()
println("─".repeat(60))
println("LIVE TESTS (require Chinese IP / VPN)")
println("─".repeat(60))

fun tryOrSkip(label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: java.io.IOException) {
        if (e.message?.contains("403") == true) {
            println("  SKIPPED: $label — 403 geo-block (need Chinese IP)")
        } else {
            println("  ERROR: $label — ${e.message}")
        }
    } catch (e: Exception) {
        println("  ERROR: $label — ${e.message}")
    }
}

tryOrSkip("mainPage")  { testMainPage() }
val firstResult = run {
    var r: String? = null
    tryOrSkip("search")    { r = testSearch("斗破苍穹") }
    r
}
val novelUrl = firstResult ?: "https://www.69shuba.com/book/10069123.htm"
val firstChapter = run {
    var r: String? = null
    tryOrSkip("loadNovel") { r = testLoad(novelUrl) }
    r
}
if (firstChapter != null) {
    tryOrSkip("loadChapter") { testLoadHtml(firstChapter) }
}

println("\n" + "=".repeat(60))
println("Done.")
