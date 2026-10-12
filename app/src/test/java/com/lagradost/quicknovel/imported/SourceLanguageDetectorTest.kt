package com.lagradost.quicknovel.imported

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sample sentences written for these tests. */
class SourceLanguageDetectorTest {
    private val zh = "清晨的山路上起了薄雾，少年背着竹篓往山上走。他想起母亲说过的话，脚步慢了下来。远处传来钟声。"
    private val zhTraditional = "清晨的山路上起了薄霧，少年背著竹簍往山上走。他想起母親說過的話，腳步慢了下來。遠處傳來鐘聲。"
    private val ja = "朝の山道には薄い霧がかかっていた。少年は籠を背負って山を登っていく。母の言葉を思い出し、足を止めた。"
    private val jaKanjiHeavy = "王国暦三百年、北方辺境伯領の城塞都市にて、騎士団長は静かに剣を抜いた。"
    private val ko = "아침 산길에는 옅은 안개가 끼어 있었다. 소년은 바구니를 메고 산을 올랐다. 어머니의 말이 떠올랐다."

    @Test
    fun detectsTheThreeLanguages() {
        assertEquals("zh", SourceLanguageDetector.detect(zh))
        assertEquals("zh", SourceLanguageDetector.detect(zhTraditional))
        assertEquals("ja", SourceLanguageDetector.detect(ja))
        assertEquals("ja", SourceLanguageDetector.detect(jaKanjiHeavy))
        assertEquals("ko", SourceLanguageDetector.detect(ko))
    }

    @Test
    fun koreanWithSomeHanjaIsKorean() {
        assertEquals("ko", SourceLanguageDetector.detect("$ko 王國 歷史"))
    }

    @Test
    fun undecidedWithoutEnoughCjkText() {
        assertNull(SourceLanguageDetector.detect("The morning road was covered in mist. The boy climbed the hill."))
        assertNull(SourceLanguageDetector.detect("第一章"))
        assertNull(SourceLanguageDetector.detect(""))
    }

    @Test
    fun overrideWinsThenDetectedThenNothing() {
        assertEquals("ja", SourceLanguageDetector.effective(detected = "zh", override = "ja"))
        assertEquals("zh", SourceLanguageDetector.effective(detected = "zh", override = null))
        assertEquals("ko", SourceLanguageDetector.effective(detected = null, override = "ko-KR"))
        assertNull(SourceLanguageDetector.effective(detected = null, override = null))
        // an untranslatable override falls back to the detection
        assertEquals("zh", SourceLanguageDetector.effective(detected = "zh", override = "fr"))
    }
}
