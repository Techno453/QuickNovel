package com.lagradost.quicknovel.translator.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GlossaryTerm::class,
        TranslatedChapter::class,
        PendingTerm::class,
        NovelContext::class,
        ChapterSummary::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class TranslatorDatabase : RoomDatabase() {
    abstract fun glossaryDao(): GlossaryDao
    abstract fun translationDao(): TranslationDao
    abstract fun pendingTermDao(): PendingTermDao
    abstract fun novelContextDao(): NovelContextDao
    abstract fun chapterSummaryDao(): ChapterSummaryDao

    /**
     * Removes everything stored for [novelId]: translated chapters, Story Bible terms, pending
     * terms, novel context and chapter summaries. Every "delete this novel" path must use this so
     * new per-novel tables cannot be forgotten.
     */
    suspend fun deleteAllForNovel(novelId: String) = withTransaction {
        translationDao().deleteAllForNovel(novelId)
        glossaryDao().deleteAllForNovel(novelId)
        pendingTermDao().deleteAll(novelId)
        novelContextDao().deleteForNovel(novelId)
        chapterSummaryDao().deleteForNovel(novelId)
    }

    companion object {
        @Volatile
        private var instance: TranslatorDatabase? = null

        /** v2: translated_chapters.source_lang (all v1 rows were Chinese). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE translated_chapters ADD COLUMN source_lang TEXT NOT NULL DEFAULT 'zh'"
                )
            }
        }

        /**
         * v3: Story Bible. Additive only:
         * - glossary_terms: gender, notes, first_chapter, auto_added, locked
         *   (rows that existed before v3 were all entered or approved by the user, so they are locked)
         * - pending_terms: gender, notes, confidence
         * - translated_chapters: glossary_violations, cjk_leaks, paragraph_warnings (nullable)
         * - new tables novel_context and chapter_summaries
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE glossary_terms ADD COLUMN gender TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE glossary_terms ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE glossary_terms ADD COLUMN first_chapter INTEGER")
                db.execSQL("ALTER TABLE glossary_terms ADD COLUMN auto_added INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE glossary_terms ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE glossary_terms SET locked = 1")

                db.execSQL("ALTER TABLE pending_terms ADD COLUMN gender TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE pending_terms ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_terms ADD COLUMN confidence REAL NOT NULL DEFAULT 0.5")

                db.execSQL("ALTER TABLE translated_chapters ADD COLUMN glossary_violations INTEGER")
                db.execSQL("ALTER TABLE translated_chapters ADD COLUMN cjk_leaks INTEGER")
                db.execSQL("ALTER TABLE translated_chapters ADD COLUMN paragraph_warnings INTEGER")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `novel_context` (`novel_id` TEXT NOT NULL, " +
                        "`premise` TEXT NOT NULL DEFAULT '', `style_notes` TEXT NOT NULL DEFAULT '', " +
                        "`name_style` TEXT NOT NULL DEFAULT '', `honorific_style` TEXT NOT NULL DEFAULT '', " +
                        "`updated_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`novel_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_summaries` (`novel_id` TEXT NOT NULL, " +
                        "`chapter_num` INTEGER NOT NULL, `summary` TEXT NOT NULL, " +
                        "`characters_present` TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(`novel_id`, `chapter_num`))"
                )
            }
        }

        /** v4: translated_chapters.used_fallback (additive; existing rows did not use a fallback). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translated_chapters ADD COLUMN used_fallback INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** SQL of [MIGRATION_4_5] (kept separate so a JVM unit test can check it). */
        val MIGRATION_4_5_SQL = listOf(
            "ALTER TABLE novel_context ADD COLUMN analyzed_at INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE novel_context ADD COLUMN analysis_chapters INTEGER NOT NULL DEFAULT 0",
            // The pre-pass is the only thing that writes a premise automatically, so a novel with a
            // premise was analyzed (manually, before v5): do not analyze it again on its own.
            "UPDATE novel_context SET analyzed_at = CASE WHEN updated_at > 0 THEN updated_at ELSE 1 END " +
                "WHERE premise != ''",
        )

        /**
         * v5: per-novel analysis state (novel_context.analyzed_at / analysis_chapters), so the
         * pre-pass runs automatically before a novel's first translation. Additive.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach { db.execSQL(it) }
            }
        }

        /** Every migration, oldest first; all registered in [getInstance]. */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun getInstance(context: Context): TranslatorDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslatorDatabase::class.java,
                    "translator_database"
                ).addMigrations(*ALL_MIGRATIONS)
                    .build().also { instance = it }
            }
        }
    }
}
