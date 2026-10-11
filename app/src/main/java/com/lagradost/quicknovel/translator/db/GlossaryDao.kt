package com.lagradost.quicknovel.translator.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary_terms WHERE novel_id = :novelId ORDER BY category, chinese")
    fun getTermsForNovel(novelId: String): Flow<List<GlossaryTerm>>

    @Query("SELECT * FROM glossary_terms WHERE novel_id = :novelId ORDER BY category, chinese")
    suspend fun getAllTermsSync(novelId: String): List<GlossaryTerm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTerm(term: GlossaryTerm): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTerms(terms: List<GlossaryTerm>)

    @Delete
    suspend fun deleteTerm(term: GlossaryTerm)

    @Query("DELETE FROM glossary_terms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM glossary_terms WHERE novel_id = :novelId")
    suspend fun deleteAllForNovel(novelId: String)

    @Query("SELECT COUNT(*) FROM glossary_terms WHERE novel_id = :novelId")
    suspend fun countForNovel(novelId: String): Int
}
