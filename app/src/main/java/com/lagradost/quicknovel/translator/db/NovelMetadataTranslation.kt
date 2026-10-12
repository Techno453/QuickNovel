package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Cached English translation of a novel's title and synopsis (Browse, search, library, preview
 * sheet and result page). Written by [com.lagradost.quicknovel.translator.MetadataTranslator];
 * kept forever and re-translated only when the source text's hash changes (or once, to replace
 * a free Google translation by an AI one; the attempt is recorded in `*_upgrade_tried_at` and
 * repeated only after 30 days). See docs/browse.md.
 *
 * The title and the synopsis are hashed separately because listings only know the title: the
 * synopsis is translated later, when the preview sheet or the result page is opened.
 */
@Entity(tableName = "novel_metadata_translations")
data class NovelMetadataTranslation(
    /** [com.lagradost.quicknovel.translator.TranslatorNovelId] ("apiName/novelName"). */
    @PrimaryKey @ColumnInfo(name = "novel_id") val novelId: String,
    /** zh / ja / ko. */
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    /** [com.lagradost.quicknovel.translator.SourceHash] of the original title. */
    @ColumnInfo(name = "title_hash") val titleHash: String,
    @ColumnInfo(name = "translated_title") val translatedTitle: String,
    /** "ai" or "google". */
    @ColumnInfo(name = "title_engine") val titleEngine: String,
    /** Hash of the original synopsis; null until the synopsis was translated. */
    @ColumnInfo(name = "synopsis_hash") val synopsisHash: String? = null,
    @ColumnInfo(name = "translated_synopsis") val translatedSynopsis: String? = null,
    @ColumnInfo(name = "synopsis_engine") val synopsisEngine: String? = null,
    /** Model id of the last AI translation stored in this row, null for Google only. */
    val model: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** When an AI translation last tried to replace the Google title (0 = never; v8). */
    @ColumnInfo(name = "title_upgrade_tried_at", defaultValue = "0") val titleUpgradeTriedAt: Long = 0,
    /** Same for the synopsis (v8). */
    @ColumnInfo(name = "synopsis_upgrade_tried_at", defaultValue = "0") val synopsisUpgradeTriedAt: Long = 0,
)

@Dao
interface NovelMetadataDao {
    @Query("SELECT * FROM novel_metadata_translations WHERE novel_id IN (:ids)")
    suspend fun get(ids: List<String>): List<NovelMetadataTranslation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<NovelMetadataTranslation>)

    @Query("DELETE FROM novel_metadata_translations WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String)

    @Query("SELECT COUNT(*) FROM novel_metadata_translations")
    suspend fun count(): Int
}
