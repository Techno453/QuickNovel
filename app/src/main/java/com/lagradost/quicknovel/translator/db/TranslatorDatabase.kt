package com.lagradost.quicknovel.translator.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GlossaryTerm::class, TranslatedChapter::class, PendingTerm::class],
    version = 2,
    exportSchema = false
)
abstract class TranslatorDatabase : RoomDatabase() {
    abstract fun glossaryDao(): GlossaryDao
    abstract fun translationDao(): TranslationDao
    abstract fun pendingTermDao(): PendingTermDao

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

        fun getInstance(context: Context): TranslatorDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslatorDatabase::class.java,
                    "translator_database"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
        }
    }
}
