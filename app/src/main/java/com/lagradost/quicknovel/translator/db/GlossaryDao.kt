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

    @Query("SELECT * FROM glossary_terms WHERE novel_id = :novelId AND chinese = :source ORDER BY id LIMIT 1")
    suspend fun getBySource(novelId: String, source: String): GlossaryTerm?

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

    @Query("SELECT COUNT(*) FROM glossary_terms WHERE novel_id = :novelId AND category = 'character'")
    suspend fun countCharacters(novelId: String): Int

    @Query("UPDATE glossary_terms SET locked = 1, auto_added = 0 WHERE id IN (:ids)")
    suspend fun lockTerms(ids: List<Long>)
}
