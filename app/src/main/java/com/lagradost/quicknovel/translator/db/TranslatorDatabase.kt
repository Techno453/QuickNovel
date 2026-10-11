package com.lagradost.quicknovel.translator.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GlossaryTerm::class, TranslatedChapter::class, PendingTerm::class],
    version = 1,
    exportSchema = false
)
abstract class TranslatorDatabase : RoomDatabase() {
    abstract fun glossaryDao(): GlossaryDao
    abstract fun translationDao(): TranslationDao
    abstract fun pendingTermDao(): PendingTermDao

    companion object {
        @Volatile
        private var instance: TranslatorDatabase? = null

        fun getInstance(context: Context): TranslatorDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslatorDatabase::class.java,
                    "translator_database"
                ).build().also { instance = it }
            }
        }
    }
}
