package com.lagradost.quicknovel.translator.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translated_chapters WHERE novel_id = :novelId AND chapter_num = :chapterNum LIMIT 1")
    suspend fun getTranslation(novelId: String, chapterNum: Int): TranslatedChapter?

    /** Closest stored translation before [chapterNum] (for continuity context). */
    @Query(
        "SELECT * FROM translated_chapters WHERE novel_id = :novelId AND chapter_num < :chapterNum " +
            "ORDER BY chapter_num DESC LIMIT 1"
    )
    suspend fun getPrevious(novelId: String, chapterNum: Int): TranslatedChapter?

    @Query("SELECT * FROM translated_chapters WHERE novel_id = :novelId ORDER BY chapter_num")
    suspend fun getAllForNovel(novelId: String): List<TranslatedChapter>

    @Query("SELECT chapter_num FROM translated_chapters WHERE novel_id = :novelId ORDER BY chapter_num")
    suspend fun getTranslatedChapterNums(novelId: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: TranslatedChapter)

    @Query("UPDATE translated_chapters SET english_text = :englishText WHERE novel_id = :novelId AND chapter_num = :chapterNum")
    suspend fun updateText(novelId: String, chapterNum: Int, englishText: String)

    @Query("DELETE FROM translated_chapters WHERE novel_id = :novelId AND chapter_num = :chapterNum")
    suspend fun deleteTranslation(novelId: String, chapterNum: Int)

    @Query("DELETE FROM translated_chapters WHERE novel_id = :novelId")
    suspend fun deleteAllForNovel(novelId: String)

    @Query("SELECT COUNT(*) FROM translated_chapters WHERE novel_id = :novelId")
    suspend fun countForNovel(novelId: String): Int
}
