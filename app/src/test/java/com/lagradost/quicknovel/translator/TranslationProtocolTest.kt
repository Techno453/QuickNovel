package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProtocolTest {

    @Test
    fun parsesTranslationTermsAndSummary() {
        val raw = """
            Lin Feng drew his sword.

            Su Wan smiled at him.
            ===TERMS===
            [{"source":"苏婉","english":"Su Wan","category":"character","gender":"female","notes":"Lin Feng's senior sister","confidence":0.9}]
            ===SUMMARY===
            Lin Feng prepares to fight while Su Wan watches. She encourages him.
            Characters: 林风, 苏婉
        """.trimIndent()
        val p = TranslationProtocol.parse(raw)
        assertEquals("Lin Feng drew his sword.\n\nSu Wan smiled at him.", p.translation)
        assertEquals(1, p.terms.size)
        val t = p.terms.single()
        assertEquals("苏婉", t.source)
        assertEquals("Su Wan", t.english)
        assertEquals("female", t.gender)
        assertEquals(0.9f, t.confidence, 1e-6f)
        assertEquals("Lin Feng prepares to fight while Su Wan watches. She encourages him.", p.summary)
        assertEquals(listOf("林风", "苏婉"), p.summaryCharacters)
        assertTrue(p.hasTermsSection && p.hasSummarySection)
    }

    @Test
    fun missingSectionsLeaveTranslationIntact() {
        val p = TranslationProtocol.parse("Just the translation.\n\nSecond paragraph.")
        assertEquals("Just the translation.\n\nSecond paragraph.", p.translation)
        assertTrue(p.terms.isEmpty())
        assertEquals("", p.summary)
        assertFalse(p.hasTermsSection)
        assertFalse(p.hasSummarySection)
    }

    @Test
    fun summaryWithoutTermsSection() {
        val p = TranslationProtocol.parse("Text here.\n===SUMMARY===\nA short summary.\nCharacters: none")
        assertEquals("Text here.", p.translation)
        assertTrue(p.terms.isEmpty())
        assertEquals("A short summary.", p.summary)
        assertTrue(p.summaryCharacters.isEmpty())
    }

    @Test
    fun fencedJsonAndDecoratedMarkers() {
        val raw = """
            ```
            He nodded.
            ```
            **=== TERMS ===**
            ```json
            [
              {"source": "青云宗", "english": "Azure Cloud Sect", "category": "faction", "confidence": "high"}
            ]
            ```
            ## ===SUMMARY===
            He agreed.
        """.trimIndent()
        val p = TranslationProtocol.parse(raw)
        assertEquals("He nodded.", p.translation)
        assertEquals("Azure Cloud Sect", p.terms.single().english)
        assertEquals("faction", p.terms.single().category)
        assertEquals(0.85f, p.terms.single().confidence, 1e-6f)
        assertEquals("He agreed.", p.summary)
    }

    @Test
    fun malformedJsonFallsBackToPerObjectParsing() {
        // Trailing garbage, a broken object and a truncated array.
        val raw = "Done.\n===TERMS===\n" +
            "[{\"source\":\"林风\",\"english\":\"Lin Feng\",\"gender\":\"male\",\"confidence\":0.8},\n" +
            " {\"source\":\"苏婉\",\"english\":\"Su Wan\" \"gender\":\"female\"},\n" +
            " {\"source\":\"黑风寨\",\"english\":\"Black Wind Fort\",\"category\":\"location\"\n" +
            "===SUMMARY===\nSomething happened. Extra chatter after."
        val p = TranslationProtocol.parse(raw)
        assertEquals("Done.", p.translation)
        val bySource = p.terms.associateBy { it.source }
        assertEquals("Lin Feng", bySource["林风"]?.english)
        assertEquals("Su Wan", bySource["苏婉"]?.english) // recovered by the field regex
        assertEquals("female", bySource["苏婉"]?.gender)
        assertEquals("Something happened. Extra chatter after.", p.summary)
    }

    @Test
    fun legacyNewMarkersAreStillAccepted() {
        val p = TranslationProtocol.parse("He met [NEW: 林动 → Lin Dong] at the gate.")
        assertEquals("He met Lin Dong at the gate.", p.translation)
        val t = p.terms.single()
        assertEquals("林动" to "Lin Dong", t.source to t.english)
        assertEquals(TranslationProtocol.LEGACY_MARKER_CONFIDENCE, t.confidence, 1e-6f)
    }

    @Test
    fun bareTrailingJsonArrayIsTreatedAsTerms() {
        val raw = "The end.\n[{\"source\":\"林风\",\"english\":\"Lin Feng\",\"confidence\":0.7}]"
        val p = TranslationProtocol.parse(raw)
        assertEquals("The end.", p.translation)
        assertEquals("Lin Feng", p.terms.single().english)
    }

    @Test
    fun emptyTermsArray() {
        val p = TranslationProtocol.parse("A.\n===TERMS===\n[]\n===SUMMARY===\nB.")
        assertTrue(p.terms.isEmpty())
        assertTrue(p.hasTermsSection)
    }

    @Test
    fun confidenceParsing() {
        assertEquals(0.5f, TranslationProtocol.parseConfidence(null), 1e-6f)
        assertEquals(0.75f, TranslationProtocol.parseConfidence("75%"), 1e-6f)
        assertEquals(0.9f, TranslationProtocol.parseConfidence("90"), 1e-6f)
        assertEquals(0.3f, TranslationProtocol.parseConfidence("low"), 1e-6f)
        assertEquals(0.6f, TranslationProtocol.parseConfidence("medium"), 1e-6f)
        assertEquals(0.5f, TranslationProtocol.parseConfidence("whatever"), 1e-6f)
    }

    @Test
    fun parsesAnalysisObjectAndFallback() {
        val ok = TranslationProtocol.parseAnalysis(
            """```json
            {"premise":"A boy joins a sect.","terms":[{"source":"林风","english":"Lin Feng","category":"character","gender":"male","notes":"protagonist","aliases":["小风"],"confidence":0.9}]}
            ```"""
        )
        assertEquals("A boy joins a sect.", ok.premise)
        assertEquals(listOf("小风"), ok.terms.single().aliases)

        val broken = TranslationProtocol.parseAnalysis(
            "{\"premise\":\"Revenge story.\",\"terms\":[{\"source\":\"林风\",\"english\":\"Lin Feng\"},{\"source\":\"x\" oops"
        )
        assertEquals("Revenge story.", broken.premise)
        assertEquals("Lin Feng", broken.terms.first().english)
    }
}
