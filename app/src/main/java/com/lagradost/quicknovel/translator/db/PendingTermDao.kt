package com.lagradost.quicknovel.translator.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingTermDao {
    @Query("SELECT * FROM pending_terms WHERE novel_id = :novelId ORDER BY chapter_num, chinese")
    suspend fun getPendingTerms(novelId: String): List<PendingTerm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(term: PendingTerm)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(terms: List<PendingTerm>)

    @Query("DELETE FROM pending_terms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_terms WHERE novel_id = :novelId")
    suspend fun deleteAll(novelId: String)

    @Query("SELECT COUNT(*) FROM pending_terms WHERE novel_id = :novelId")
    suspend fun countForNovel(novelId: String): Int
}
