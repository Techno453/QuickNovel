package com.lagradost.quicknovel.imported

import com.lagradost.quicknovel.mvvm.logError
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.TOCReference
import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * The chapters of an EPUB as the reader shows them ([EpubChapterPlan]) and their HTML. The reader
 * ([com.lagradost.quicknovel.RegularBook]) and the translation pipeline for imported books both
 * read chapters through this class, so the source the translator hashes and translates is the
 * text the reader displays.
 */
class EpubChapters(val book: EpubBook) {
    val chapters: List<EpubChapterPlan.Chapter>

    init {
        val spineRefs = book.spine.spineReferences
        fun entry(ref: TOCReference): EpubChapterPlan.TocEntry = EpubChapterPlan.TocEntry(
            title = ref.title,
            spineIndex = book.spine.getResourceIndex(ref.resource),
            children = ref.children.orEmpty().map(::entry),
        )
        chapters = EpubChapterPlan.plan(
            toc = book.tableOfContents.tocReferences.orEmpty().map(::entry),
            spine = spineRefs.map { EpubChapterPlan.SpineItem(it.resource?.title, it.isLinear) },
        )
    }

    val size: Int get() = chapters.size

    fun title(index: Int): String? = chapters.getOrNull(index)?.title

    /** The chapter's body HTML, image paths resolved against the book (as the reader needs them). */
    fun html(index: Int): String {
        val chapter = chapters.getOrNull(index) ?: return ""
        val builder = StringBuilder()
        for (i in chapter.spineIndices) {
            try {
                val ref = book.spine.spineReferences[i]
                val html = ref.resource.reader.readText()
                val doc = Jsoup.parse(html)
                val basePath = ref.resource.href.substringBeforeLast("/", "")

                doc.select("img, image").forEach { img ->
                    val attrName = if (img.tagName() == "image") "xlink:href" else "src"
                    var src = img.attr(attrName)
                    if (src.isNotEmpty() && !src.startsWith("http") && !src.startsWith("data:")) {
                        try {
                            val decodedSrc = URLDecoder.decode(src, "UTF-8")
                            src = resolveRelativePath(basePath, decodedSrc)
                        } catch (e: Throwable) {
                            logError(e)
                        }
                    }
                    if (img.tagName() == "image") {
                        val newImg = doc.createElement("img")
                        newImg.attr("src", src)
                        img.replaceWith(newImg)
                    } else {
                        img.attr("src", src)
                    }
                }

                builder.append(doc.body().html())
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return builder.toString()
    }

    companion object {
        fun resolveRelativePath(basePath: String, relativePath: String): String {
            val cleanRelative = relativePath.substringBefore("?").substringBefore("#")

            val fullPath = if (cleanRelative.startsWith("/") || basePath.isEmpty()) {
                cleanRelative
            } else {
                "$basePath/$cleanRelative"
            }

            val resolvedParts = ArrayDeque<String>()
            for (part in fullPath.split("/")) {
                when (part) {
                    "", "." -> continue
                    ".." -> if (resolvedParts.isNotEmpty()) resolvedParts.removeLast()
                    else -> resolvedParts.addLast(part)
                }
            }
            return resolvedParts.joinToString("/")
        }
    }
}
