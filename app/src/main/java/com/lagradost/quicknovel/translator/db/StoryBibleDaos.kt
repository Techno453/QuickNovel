package com.lagradost.quicknovel.translator.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NovelContextDao {
    @Query("SELECT * FROM novel_context WHERE novel_id = :novelId LIMIT 1")
    suspend fun get(novelId: String): NovelContext?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(context: NovelContext)

    @Query("DELETE FROM novel_context WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String)
}

@Dao
interface ChapterSummaryDao {
    @Query("SELECT * FROM chapter_summaries WHERE novel_id = :novelId AND chapter_num = :chapterNum LIMIT 1")
    suspend fun get(novelId: String, chapterNum: Int): ChapterSummary?

    /** Closest summary before [chapterNum] (the immediately previous chapter may be untranslated). */
    @Query(
        "SELECT * FROM chapter_summaries WHERE novel_id = :novelId AND chapter_num < :chapterNum " +
            "ORDER BY chapter_num DESC LIMIT 1"
    )
    suspend fun getPrevious(novelId: String, chapterNum: Int): ChapterSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: ChapterSummary)

    @Query("DELETE FROM chapter_summaries WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String)
}
