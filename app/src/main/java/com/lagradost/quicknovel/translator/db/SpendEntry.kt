package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One paid LLM call (or, for Batch API results, one chapter's share of a batch) in the spend
 * ledger. Written by [com.lagradost.quicknovel.translator.SpendLedger].
 */
@Entity(tableName = "spend_entries", indices = [Index(value = ["timestamp"])])
data class SpendEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "novel_id") val novelId: String? = null,
    @ColumnInfo(name = "chapter_num") val chapterNum: Int? = null,
    /** [com.lagradost.quicknovel.translator.SpendKind.id]: translate, analysis, read_ahead, batch, search, other. */
    val kind: String,
    val provider: String,
    val model: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int,
    val usd: Double,
    /** False when the model's price was unknown ([usd] is then 0). */
    val priced: Boolean,
    /** Local batch_jobs.id for batch results and their live re-translations. */
    @ColumnInfo(name = "batch_id") val batchId: Long? = null,
)

/** Sum of USD for one group (a novel or a model). */
data class SpendGroupTotal(
    val name: String?,
    val usd: Double,
    val calls: Int,
)

@Dao
interface SpendDao {
    @Insert
    suspend fun insert(entry: SpendEntry): Long

    @Query("SELECT COALESCE(SUM(usd), 0) FROM spend_entries WHERE timestamp >= :since")
    suspend fun totalSince(since: Long): Double

    @Query("SELECT COUNT(*) FROM spend_entries WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM spend_entries WHERE priced = 0")
    suspend fun countUnpriced(): Int

    @Query(
        "SELECT novel_id AS name, SUM(usd) AS usd, COUNT(*) AS calls FROM spend_entries " +
            "GROUP BY novel_id ORDER BY usd DESC"
    )
    suspend fun totalsByNovel(): List<SpendGroupTotal>

    @Query(
        "SELECT model AS name, SUM(usd) AS usd, COUNT(*) AS calls FROM spend_entries " +
            "GROUP BY model ORDER BY usd DESC"
    )
    suspend fun totalsByModel(): List<SpendGroupTotal>

    @Query("SELECT * FROM spend_entries ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<SpendEntry>

    @Query("SELECT COALESCE(SUM(usd), 0) FROM spend_entries WHERE batch_id = :batchId")
    suspend fun totalForBatch(batchId: Long): Double

    @Query("DELETE FROM spend_entries")
    suspend fun clear()
}
