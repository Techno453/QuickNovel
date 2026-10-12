package com.lagradost.quicknovel.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline checks of the bench's metric code (always runs; no network). */
class BenchMetricsTest {
    private val sample = Sample(
        "zh-test", "zh", "test", "unit",
        "萧炎看着天空。\n萧薰儿笑了。\n乌坦城很安静。", 20
    )
    private val bible = Bible(
        "zh-test", entries = listOf(
            BibleEntry("萧炎", "Xiao Yan", "character", "male"),
            BibleEntry("萧薰儿", "Xiao Xun'er", "character", "female"),
            BibleEntry("乌坦城", "Wutan City", "location"),
            BibleEntry("斗气", "Dou Qi", "technique"), // not in the sample text
        )
    )

    @Test
    fun termMatchingAllowsPossessiveAndPlural() {
        assertTrue(MetricsCalculator.containsTerm("It was Xiao Yan's sword.", "Xiao Yan"))
        assertTrue(MetricsCalculator.containsTerm("XIAO YAN looked up", "Xiao Yan"))
        assertTrue(MetricsCalculator.containsTerm("two Elders", "Elder"))
        assertFalse(MetricsCalculator.containsTerm("Xiao Yanzi", "Xiao Yan"))
    }

    @Test
    fun computesAdherenceLeaksParagraphsAndGender() {
        val out = "Xiao Yan looked at the sky; she sighed.\nXiao Xun'er smiled at her own reflection.\nThe town of 乌坦 was quiet."
        val m = MetricsCalculator.compute(sample, bible, out)
        assertEquals(3, m.termsChecked)
        assertEquals(listOf("乌坦城 → Wutan City"), m.termMisses)
        assertEquals(2, m.cjkLeak)
        assertEquals(1.0, m.paragraphFidelity, 1e-9)
        assertEquals(1, m.genderMismatches) // "she" in Xiao Yan's sentence
        assertEquals(2, m.genderChecked)
    }

    @Test
    fun scrubRemovesKeys() {
        Secrets.register("sk-test-ABCDEFGHIJKLMNOP")
        val s = Secrets.scrub("Incorrect API key provided: sk-test-ABCDEFGHIJKLMNOP and sk-proj-zz12****abcd")
        assertFalse(s.contains("ABCDEFGHIJKLMNOP"))
        assertFalse(s.contains("zz12"))
        assertFalse(Secrets.mask("sk-test-ABCDEFGHIJKLMNOP").contains("sk-test"))
    }

    @Test
    fun effortStepDown() {
        assertEquals("minimal", MeteringInterceptor.nextEffort("none"))
        assertEquals("low", MeteringInterceptor.nextEffort("minimal"))
        assertEquals(null, MeteringInterceptor.nextEffort("low"))
    }
}
