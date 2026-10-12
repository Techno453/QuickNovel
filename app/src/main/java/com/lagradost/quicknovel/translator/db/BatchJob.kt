package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * An overnight pre-translation submitted to the OpenAI Batch API (one per novel and range).
 * [status] is a [com.lagradost.quicknovel.translator.batch.BatchStatus] name.
 */
@Entity(tableName = "batch_jobs")
data class BatchJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo(name = "api_name") val apiName: String,
    @ColumnInfo(name = "novel_name") val novelName: String,
    @ColumnInfo(name = "novel_author") val novelAuthor: String? = null,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    val model: String,
    @ColumnInfo(name = "preset_id") val presetId: String? = null,
    val status: String,
    @ColumnInfo(name = "openai_batch_id") val openaiBatchId: String? = null,
    @ColumnInfo(name = "input_file_id") val inputFileId: String? = null,
    @ColumnInfo(name = "output_file_id") val outputFileId: String? = null,
    @ColumnInfo(name = "error_file_id") val errorFileId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    /** First and last chapter index of the requested range (inclusive). */
    @ColumnInfo(name = "from_chapter") val fromChapter: Int,
    @ColumnInfo(name = "to_chapter") val toChapter: Int,
    /** Requests (chunks) in the batch. */
    @ColumnInfo(name = "request_count") val requestCount: Int,
    /** OpenAI's request_counts.completed / failed while running. */
    @ColumnInfo(name = "completed_count") val completedCount: Int = 0,
    @ColumnInfo(name = "failed_count") val failedCount: Int = 0,
    @ColumnInfo(name = "estimated_usd") val estimatedUsd: Double,
    /** Spend recorded for this batch: results at the batch discount plus live re-translations of failed passages. */
    @ColumnInfo(name = "actual_usd") val actualUsd: Double? = null,
    val error: String? = null,
)

/** One chapter of a [BatchJob]: its chunk sources (to reassemble the results) and outcome. */
@Entity(tableName = "batch_chapters", primaryKeys = ["batch_id", "chapter_num"])
data class BatchChapter(
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "chapter_num") val chapterNum: Int,
    @ColumnInfo(name = "chapter_name") val chapterName: String,
    /** [com.lagradost.quicknovel.translator.SourceHash] of the chapter source the requests were built from. */
    @ColumnInfo(name = "source_hash") val sourceHash: String,
    /** JSON array of the chunk source texts, in order (chunk i = custom_id …|i). */
    @ColumnInfo(name = "chunks_json") val chunksJson: String,
    /** [com.lagradost.quicknovel.translator.batch.BatchChapterStatus] name. */
    val status: String,
    val error: String? = null,
)

@Dao
interface BatchDao {
    @Insert
    suspend fun insertJob(job: BatchJob): Long

    @Update
    suspend fun updateJob(job: BatchJob)

    @Query("SELECT * FROM batch_jobs WHERE id = :id")
    suspend fun getJob(id: Long): BatchJob?

    @Query("SELECT * FROM batch_jobs WHERE status IN (:statuses) ORDER BY created_at")
    suspend fun jobsWithStatus(statuses: List<String>): List<BatchJob>

    @Query("SELECT * FROM batch_jobs WHERE novel_id = :novelId ORDER BY created_at DESC LIMIT 1")
    suspend fun latestForNovel(novelId: String): BatchJob?

    @Query("SELECT * FROM batch_jobs WHERE novel_id = :novelId AND status IN (:statuses)")
    suspend fun jobsForNovel(novelId: String, statuses: List<String>): List<BatchJob>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<BatchChapter>)

    @Query("SELECT * FROM batch_chapters WHERE batch_id = :batchId ORDER BY chapter_num")
    suspend fun chapters(batchId: Long): List<BatchChapter>

    @Query("UPDATE batch_chapters SET status = :status, error = :error WHERE batch_id = :batchId AND chapter_num = :chapterNum")
    suspend fun setChapterStatus(batchId: Long, chapterNum: Int, status: String, error: String?)

    @Query("SELECT chapter_num FROM batch_chapters WHERE batch_id IN (:batchIds)")
    suspend fun chapterNumsOf(batchIds: List<Long>): List<Int>

    @Query("DELETE FROM batch_chapters WHERE batch_id IN (SELECT id FROM batch_jobs WHERE novel_id = :novelId AND status IN (:statuses))")
    suspend fun deleteChaptersForNovel(novelId: String, statuses: List<String>)

    @Query("DELETE FROM batch_jobs WHERE novel_id = :novelId AND status IN (:statuses)")
    suspend fun deleteJobsForNovel(novelId: String, statuses: List<String>)
}
