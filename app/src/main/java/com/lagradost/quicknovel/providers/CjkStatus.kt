package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus

/**
 * Maps the serialization status that Chinese, Japanese and Korean sites print (连载 / 完结,
 * 連載中 / 完結済, 연재중 / 완결, ...) to the app's [ReleaseStatus]. The generic
 * `LoadResponse.setStatus(String)` only knows English words, so CJK providers passed it text it
 * never matched and the status stayed empty.
 *
 * Matching is by substring, so a label such as "71.35万字 | 连载" or "状态：已完结" works. Pass the
 * status field itself, not a whole page: site names like 全本小说网 contain a status word.
 * Pure, covered by `CjkStatusTest`.
 */
object CjkStatus {
    /** "Not finished" wording that contains a completed word; checked first. */
    private val notFinished = listOf("未完结", "未完結", "未完本", "미완결")

    private val completed = listOf(
        "已完结", "完结", "已完本", "完本", "全本", "已完成", // zh-Hans
        "完結", "完本", // zh-Hant / ja (完結済 contains 完結)
        "완결", // ko
    )

    private val ongoing = listOf(
        "连载", "連載", // zh / ja (连载中, 連載中 contain these)
        "연재", // ko (연재중)
    )

    private val paused = listOf("断更", "停更", "休載", "休止", "휴재")

    fun parse(text: String?): ReleaseStatus? {
        val t = text?.trim()?.ifEmpty { null } ?: return null
        return when {
            notFinished.any { t.contains(it) } -> ReleaseStatus.Ongoing
            completed.any { t.contains(it) } -> ReleaseStatus.Completed
            paused.any { t.contains(it) } -> ReleaseStatus.Paused
            ongoing.any { t.contains(it) } -> ReleaseStatus.Ongoing
            else -> null
        }
    }
}
