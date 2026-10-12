package com.lagradost.quicknovel.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Offline checks of the judge panel (always runs; no network). */
class BenchJudgeTest {
    @Test
    fun selfJudgingMatchesNormalizedIds() {
        assertTrue(isSelfJudgment("google/gemini-3.1-flash-lite", "gemini-3.1-flash-lite"))
        assertTrue(isSelfJudgment("gpt-5.4-mini", "GPT-5.4-mini"))
        assertFalse(isSelfJudgment("gpt-6-luna", "gpt-6-sol"))
        assertFalse(isSelfJudgment("deepseek/deepseek-v4.1-flash", "deepseek/deepseek-v4.1"))
    }

    @Test
    fun parsesJudgeRepliesRobustly() {
        val plain = Judge.parseScores("""{"accuracy": 8, "fluency": 9, "consistency": 7, "note": "Drops one line."}""")!!
        assertEquals(8.0, plain.accuracy, 0.0)
        assertEquals("Drops one line.", plain.note)
        val fenced = Judge.parseScores("Here you go:\n```json\n{\"accuracy\": \"8/10\", \"fluency\": \"9\", \"terminology\": 6, \"note\": \"ok\"}\n```")!!
        assertEquals(8.0, fenced.accuracy, 0.0)
        assertEquals(6.0, fenced.consistency, 0.0)
        val broken = Judge.parseScores("""{"accuracy": 7, "fluency": 12, "consistency": 0, "note": "trailing comma",}""")!!
        assertEquals(10.0, broken.fluency, 0.0) // clamped
        assertEquals(1.0, broken.consistency, 0.0)
        assertNull(Judge.parseScores("I cannot grade this."))
    }

    @Test
    fun spearmanHandlesTies() {
        assertEquals(1.0, Stats.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(10.0, 20.0, 30.0, 40.0))!!, 1e-9)
        assertEquals(-1.0, Stats.spearman(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0))!!, 1e-9)
        assertEquals(listOf(1.5, 1.5, 3.0), Stats.ranksDescending(listOf(5.0, 5.0, 1.0)))
        assertNull(Stats.spearman(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0)))
    }

    @Test
    fun boardExcludesSelfScoresFromPanel() {
        fun s(j: String, m: String, v: Double) = PanelScore(j, "zh", m, "r", v, v, v)
        val scores = listOf(
            s("gpt-6-luna", "gpt-6-luna", 10.0), s("judge-b", "gpt-6-luna", 6.0), s("google/judge-c", "gpt-6-luna", 8.0),
            s("gpt-6-luna", "judge-c", 9.0), s("judge-b", "judge-c", 5.0), s("google/judge-c", "judge-c", 10.0),
        )
        val board = JudgeBoard(
            listOf(JudgeInfo("gpt-6-luna", PanelScore.KIND_LEGACY), JudgeInfo("judge-b", PanelScore.KIND_LLM), JudgeInfo("google/judge-c", PanelScore.KIND_LLM)),
            scores, "gpt-6-luna", listOf("zh"), listOf("gpt-6-luna", "judge-c"),
        )
        assertEquals(7.0, board.cellPanelRaw("zh", "gpt-6-luna")!!, 1e-9) // 6 and 8, not luna's own 10
        assertEquals(10.0, board.baselineMean("gpt-6-luna")!!, 1e-9) // luna-only keeps its self-score
        assertEquals(7.0, board.cellPanelRaw("zh", "judge-c")!!, 1e-9) // 9 and 5, not judge-c's own 10
        assertEquals(4.0, board.cellSpread("zh", "judge-c")!!, 1e-9)
        assertEquals(setOf("gpt-6-luna" to "gpt-6-luna", "google/judge-c" to "judge-c"), board.excluded.map { it.judge to it.modelId }.toSet())
    }

    @Test
    fun biasAdjustmentKeepsAHarshJudgeFromLiftingItsOwnModel() {
        // Both models are equally good (7); judge "harsh" is 2 points stricter and owns model "harsh".
        fun s(j: String, m: String, smp: String, v: Double) = PanelScore(j, smp, m, "r", v, v, v)
        val samples = listOf("a", "b")
        val scores = samples.flatMap { smp ->
            listOf(s("harsh", "other", smp, 5.0), s("j2", "harsh", smp, 7.0), s("j2", "other", smp, 7.0),
                s("j3", "harsh", smp, 7.0), s("j3", "other", smp, 7.0))
        }
        val board = JudgeBoard(listOf("harsh", "j2", "j3").map { JudgeInfo(it, PanelScore.KIND_LLM) }, scores, null, samples, listOf("harsh", "other"))
        val rawGap = board.panelMeanRaw("harsh")!! - board.panelMeanRaw("other")!!
        val adjGap = board.panelMean("harsh")!! - board.panelMean("other")!!
        assertEquals(2.0 / 3, rawGap, 1e-9)
        assertTrue("adjusted gap $adjGap should shrink", kotlin.math.abs(adjGap) < rawGap / 3)
        assertTrue(board.judgeBias.getValue("harsh") < 0)
    }

    @Test
    fun loadsManualJudgeFiles() {
        val dir = Files.createTempDirectory("manual-judges").toFile()
        try {
            File(dir, "alex.json").writeText("""[{"sample": "zh", "model": "gpt-6-luna", "accuracy": 8, "fluency": 7, "consistency": 9, "note": "fine"}]""")
            File(dir, "broken.json").writeText("not json")
            val notes = mutableListOf<String>()
            val manual = ManualJudges.load(dir, notes)
            assertEquals(setOf("alex"), manual.keys)
            assertEquals(8.0, manual.getValue("alex").single().average!!, 1e-9)
            assertEquals(PanelScore.KIND_MANUAL, manual.getValue("alex").single().kind)
            assertTrue(notes.single().contains("broken.json"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
