package com.lagradost.quicknovel.imported

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes an imported .txt file. CJK web-novel TXT files come in UTF-8 (with or without BOM),
 * UTF-16 (with BOM), GB18030/GBK, Big5, Shift_JIS, EUC-JP or EUC-KR.
 *
 * Order: BOM → strict UTF-8 → every legacy candidate that decodes without errors, scored by how
 * many of the most common characters of its language the text contains (a wrong legacy decoding
 * still "decodes" but produces rare characters). Falls back to lenient UTF-8.
 */
object TextDecoding {
    data class Decoded(val text: String, val charset: String)

    /** Legacy encodings in tie-break order. */
    private val CANDIDATES = listOf("GB18030", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR")

    /** Very frequent characters per script; a correct decoding is full of them. */
    private const val COMMON =
        // Chinese (simplified + traditional)
        "的一是不了人我在有他这這个個们們来來到说說着著上大中你地就出要她也时時道" +
        // Japanese kana
        "のにはをたがでてとしいるなかれっもうこまだよ" +
        // Korean
        "이다는에의가고하을를지기서도한로그있"

    private val commonSet = COMMON.codePoints().toArray().toHashSet()

    fun decode(bytes: ByteArray): Decoded {
        bom(bytes)?.let { return it }
        strict(bytes, Charsets.UTF_8)?.let { return Decoded(it, "UTF-8") }

        var best: Decoded? = null
        var bestScore = -1.0
        for (name in CANDIDATES) {
            val cs = try {
                if (!Charset.isSupported(name)) continue
                Charset.forName(name)
            } catch (e: Exception) {
                continue
            }
            val text = strict(bytes, cs) ?: continue
            val s = score(text)
            if (s > bestScore) {
                bestScore = s
                best = Decoded(text, name)
            }
        }
        return best ?: Decoded(String(bytes, Charsets.UTF_8), "UTF-8 (lenient)")
    }

    /** Share of common characters among the non-ASCII, non-space characters. */
    fun score(text: String): Double {
        var common = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp > 0x7F && !Character.isWhitespace(cp)) {
                if (cp in commonSet) common++ else other++
            }
            i += Character.charCount(cp)
        }
        val total = common + other
        return if (total == 0) 0.0 else common.toDouble() / total
    }

    private fun bom(bytes: ByteArray): Decoded? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8")
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
        else -> null
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
