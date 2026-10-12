package com.lagradost.quicknovel.translator.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {
    /**
     * Raw row of a chapter, preview or not: callers decide with
     * `ChapterTranslationRepository.servable`. The queries below that answer "is it translated"
     * leave preview rows (`preview = 1`, the result page's Preview) out.
     */
    @Query("SELECT * FROM translated_chapters WHERE novel_id = :novelId AND chapter_num = :chapterNum LIMIT 1")
    suspend fun getTranslation(novelId: String, chapterNum: Int): TranslatedChapter?

    /** Closest stored translation before [chapterNum] (for continuity context; previews left out). */
    @Query(
        "SELECT * FROM translated_chapters WHERE novel_id = :novelId AND chapter_num < :chapterNum AND preview = 0 " +
            "ORDER BY chapter_num DESC LIMIT 1"
    )
    suspend fun getPrevious(novelId: String, chapterNum: Int): TranslatedChapter?

    @Query("SELECT * FROM translated_chapters WHERE novel_id = :novelId ORDER BY chapter_num")
    suspend fun getAllForNovel(novelId: String): List<TranslatedChapter>

    /** Translated chapters (preview rows are not translations). */
    @Query("SELECT chapter_num FROM translated_chapters WHERE novel_id = :novelId AND preview = 0 ORDER BY chapter_num")
    suspend fun getTranslatedChapterNums(novelId: String): List<Int>

    /** Chapters that only have a preview translation. */
    @Query("SELECT chapter_num FROM translated_chapters WHERE novel_id = :novelId AND preview = 1 ORDER BY chapter_num")
    suspend fun getPreviewChapterNums(novelId: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: TranslatedChapter)

    @Query("UPDATE translated_chapters SET english_text = :englishText WHERE novel_id = :novelId AND chapter_num = :chapterNum")
    suspend fun updateText(novelId: String, chapterNum: Int, englishText: String)

    @Query("DELETE FROM translated_chapters WHERE novel_id = :novelId AND chapter_num = :chapterNum")
    suspend fun deleteTranslation(novelId: String, chapterNum: Int)

    @Query("DELETE FROM translated_chapters WHERE novel_id = :novelId")
    suspend fun deleteAllForNovel(novelId: String)

    @Query("SELECT COUNT(*) FROM translated_chapters WHERE novel_id = :novelId AND preview = 0")
    suspend fun countForNovel(novelId: String): Int

    @Query("UPDATE translated_chapters SET source_hash = :hash, stale = :stale WHERE novel_id = :novelId AND chapter_num = :chapterNum")
    suspend fun setSourceState(novelId: String, chapterNum: Int, hash: String, stale: Boolean)

    @Query("UPDATE translated_chapters SET stale = :stale WHERE novel_id = :novelId AND chapter_num = :chapterNum")
    suspend fun setStale(novelId: String, chapterNum: Int, stale: Boolean)

    @Query("SELECT chapter_num FROM translated_chapters WHERE novel_id = :novelId AND stale = 1 AND preview = 0 ORDER BY chapter_num")
    suspend fun getStaleChapterNums(novelId: String): List<Int>

    /** Stale chapters of [novelId], updated whenever translated_chapters changes. */
    @Query("SELECT chapter_num FROM translated_chapters WHERE novel_id = :novelId AND stale = 1 AND preview = 0 ORDER BY chapter_num")
    fun observeStaleChapterNums(novelId: String): kotlinx.coroutines.flow.Flow<List<Int>>
}
