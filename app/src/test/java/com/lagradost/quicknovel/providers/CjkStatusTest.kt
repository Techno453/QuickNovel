package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [CjkStatus]: the status words CJK sites print, mapped to [ReleaseStatus]. */
class CjkStatusTest {
    private fun assertStatus(expected: ReleaseStatus?, vararg texts: String?) {
        for (text in texts) assertEquals("'$text'", expected, CjkStatus.parse(text))
    }

    @Test
    fun chineseOngoing() = assertStatus(ReleaseStatus.Ongoing, "连载", "连载中", " 连载 ", "71.35万字 | 连载", "状态：连载中")

    @Test
    fun chineseCompleted() = assertStatus(ReleaseStatus.Completed, "完结", "已完结", "完本", "全本", "已完本", "状态：已完结")

    @Test
    fun traditionalChinese() = assertStatus(ReleaseStatus.Completed, "完結", "已完結")

    @Test
    fun japanese() {
        assertStatus(ReleaseStatus.Ongoing, "連載中", "連載")
        assertStatus(ReleaseStatus.Completed, "完結", "完結済")
        assertStatus(ReleaseStatus.Paused, "連載休止中", "休載中")
    }

    @Test
    fun korean() {
        assertStatus(ReleaseStatus.Ongoing, "연재중", "연재", "미완결")
        assertStatus(ReleaseStatus.Completed, "완결", "완결작")
    }

    @Test
    fun notFinishedIsOngoing() = assertStatus(ReleaseStatus.Ongoing, "未完结", "未完結")

    @Test
    fun unknownTextIsNull() = assertStatus(null, null, "", "   ", "短編", "玄幻小说")
}
