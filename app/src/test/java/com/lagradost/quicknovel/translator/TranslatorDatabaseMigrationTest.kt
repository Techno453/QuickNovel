package com.lagradost.quicknovel.translator

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lagradost.quicknovel.translator.db.NovelContext
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * JVM checks of the Room migrations (no device / Robolectric here): the migration chain is
 * complete and v4→v5 issues the expected additive SQL. Room validates the resulting schema
 * against the entities at runtime on first open.
 */
class TranslatorDatabaseMigrationTest {

    /** A SupportSQLiteDatabase that only records execSQL calls. */
    private fun recordingDb(statements: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> statements += args!![0] as String
                "toString" -> return@newProxyInstance "recordingDb"
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
                else -> throw UnsupportedOperationException(method.name)
            }
            null
        } as SupportSQLiteDatabase

    @Test
    fun migrationChainCoversEveryVersion() {
        val migrations = TranslatorDatabase.ALL_MIGRATIONS
        assertEquals(1, migrations.first().startVersion)
        migrations.toList().zipWithNext().forEach { (a, b) -> assertEquals(a.endVersion, b.startVersion) }
        assertEquals(6, migrations.last().endVersion)
    }

    @Test
    fun migration5To6IsAdditive() {
        val statements = mutableListOf<String>()
        TranslatorDatabase.MIGRATION_5_6.migrate(recordingDb(statements))
        assertEquals(TranslatorDatabase.MIGRATION_5_6_SQL, statements)
        assertEquals(
            listOf(
                "ALTER TABLE translated_chapters ADD COLUMN source_hash TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE translated_chapters ADD COLUMN stale INTEGER NOT NULL DEFAULT 0",
            ),
            statements.take(2),
        )
        // Nothing is dropped, renamed or rewritten.
        statements.forEach { sql ->
            assertTrue(sql, sql.startsWith("ALTER TABLE") && sql.contains("ADD COLUMN") || sql.startsWith("CREATE "))
        }
        listOf("spend_entries", "batch_jobs", "batch_chapters", "index_spend_entries_timestamp").forEach { name ->
            assertTrue(name, statements.any { it.contains("`$name`") })
        }
    }

    /**
     * The tables created by the migration must be exactly what Room creates for a fresh install
     * (Room validates the schema on open). Compares against the KSP-generated
     * TranslatorDatabase_Impl when it exists (it does after any build of the app module).
     */
    @Test
    fun migration5To6MatchesRoomsGeneratedSchema() {
        val generated = listOf(
            "build/generated/ksp/debug/kotlin/com/lagradost/quicknovel/translator/db/TranslatorDatabase_Impl.kt",
            "app/build/generated/ksp/debug/kotlin/com/lagradost/quicknovel/translator/db/TranslatorDatabase_Impl.kt",
        ).map { java.io.File(it) }.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("KSP output not found", generated != null)
        val impl = generated!!.readText()
        TranslatorDatabase.MIGRATION_5_6_SQL.filter { it.startsWith("CREATE") }.forEach { sql ->
            assertTrue("Room creates a different schema than:\n$sql", impl.contains(sql))
        }
        assertTrue(impl.contains("`source_hash` TEXT NOT NULL DEFAULT ''"))
        assertTrue(impl.contains("`stale` INTEGER NOT NULL DEFAULT 0"))
    }

    @Test
    fun translationsDefaultToAnUnknownHashAndNotStale() {
        val row = com.lagradost.quicknovel.translator.db.TranslatedChapter("n", 1, "<p>x</p>")
        assertEquals("", row.sourceHash)
        assertFalse(row.stale)
    }

    @Test
    fun migration4To5AddsAnalysisStateAndBackfillsAnalyzedNovels() {
        val statements = mutableListOf<String>()
        TranslatorDatabase.MIGRATION_4_5.migrate(recordingDb(statements))
        assertEquals(TranslatorDatabase.MIGRATION_4_5_SQL, statements)
        assertEquals(
            listOf(
                "ALTER TABLE novel_context ADD COLUMN analyzed_at INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE novel_context ADD COLUMN analysis_chapters INTEGER NOT NULL DEFAULT 0",
            ),
            statements.take(2),
        )
        // Novels that already have a premise were analyzed manually before v5.
        val backfill = statements[2]
        assertTrue(backfill.startsWith("UPDATE novel_context SET analyzed_at"))
        assertTrue(backfill.contains("WHERE premise != ''"))
    }

    @Test
    fun analysisStateDefaultsToNeverAnalyzed() {
        val fresh = NovelContext(novelId = "n")
        assertEquals(0L, fresh.analyzedAt)
        assertEquals(0, fresh.analysisChapters)
        assertFalse(fresh.isAnalyzed)
        assertTrue(fresh.copy(analyzedAt = 1).isAnalyzed)
    }

    @Test
    fun analysisStateIsNotExportedWithTheStoryBible() {
        val json = StoryBibleJson.export(
            emptyList(),
            NovelContext(novelId = "n", premise = "A boy.", analyzedAt = 123L, analysisChapters = 3),
        )
        assertFalse(json.contains("analyzedAt"))
        assertFalse(json.contains("analysisChapters"))
        assertTrue(json.contains("A boy."))
        val imported = StoryBibleJson.import(json, "other")
        assertEquals(0L, imported.context!!.analyzedAt)
    }
}
