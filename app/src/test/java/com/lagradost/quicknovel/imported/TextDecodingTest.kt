package com.lagradost.quicknovel.imported

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

/** Sample paragraphs written for these tests. */
class TextDecodingTest {
    private val zh = "第一章 风起\n清晨的山路上起了薄雾，少年背着竹篓往山上走。他想起了母亲说的话，就在路边坐了下来。"
    private val zhTraditional = "第一章 風起\n清晨的山路上起了薄霧，少年背著竹簍往山上走。他想起了母親說的話，就在路邊坐了下來。"
    private val ja = "第一話 春\n朝の山道には薄い霧がかかっていた。少年は籠を背負って山を登っていく。母の言葉を思い出して、道の端に座った。"
    private val ko = "제1화 시작\n아침 산길에는 옅은 안개가 끼어 있었다. 소년은 바구니를 메고 산을 올랐다. 어머니의 말이 떠올라 길가에 앉았다."

    private fun roundTrip(text: String, charset: String) {
        val decoded = TextDecoding.decode(text.toByteArray(Charset.forName(charset)))
        assertEquals("decoded as ${decoded.charset}", text, decoded.text)
    }

    @Test
    fun utf8() {
        roundTrip(zh, "UTF-8")
        roundTrip(ja, "UTF-8")
        roundTrip(ko, "UTF-8")
    }

    @Test
    fun byteOrderMarks() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + zh.toByteArray(Charsets.UTF_8)
        assertEquals(zh, TextDecoding.decode(utf8Bom).text)
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + ja.toByteArray(Charsets.UTF_16LE)
        assertEquals(ja, TextDecoding.decode(utf16le).text)
        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + ko.toByteArray(Charsets.UTF_16BE)
        assertEquals(ko, TextDecoding.decode(utf16be).text)
    }

    @Test
    fun legacyChinese() {
        roundTrip(zh, "GB18030")
        roundTrip(zh, "GBK")
        roundTrip(zhTraditional, "Big5")
    }

    @Test
    fun legacyJapanese() {
        roundTrip(ja, "Shift_JIS")
        roundTrip(ja, "EUC-JP")
    }

    @Test
    fun legacyKorean() {
        roundTrip(ko, "EUC-KR")
    }

    @Test
    fun plainAscii() {
        assertEquals("Chapter 1\nHello.", TextDecoding.decode("Chapter 1\nHello.".toByteArray()).text)
    }
}
