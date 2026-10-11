package com.lagradost.quicknovel.translator.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translated_chapters WHERE novel_id = :novelId AND chapter_num = :chapterNum LIMIT 1")
    suspend fun getTranslation(novelId: String, chapterNum: Int): TranslatedChapter?

    @Query("SELECT chapter_num FROM translated_chapters WHERE novel_id = :novelId ORDER BY chapter_num")
    suspend fun getTranslatedChapterNums(novelId: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: TranslatedChapter)

    @Query("DELETE FROM translated_chapters WHERE novel_id = :novelId AND chapter_num = :chapterNum")
    suspend fun deleteTranslation(novelId: String, chapterNum: Int)

    @Query("DELETE FROM translated_chapters WHERE novel_id = :novelId")
    suspend fun deleteAllForNovel(novelId: String)

    @Query("SELECT COUNT(*) FROM translated_chapters WHERE novel_id = :novelId")
    suspend fun countForNovel(novelId: String): Int
}
