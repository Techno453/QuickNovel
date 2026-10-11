package com.lagradost.quicknovel.translator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTranslatorTest {

    @Test
    fun scriptDetectionForSearchQueries() {
        assertTrue(SearchQueryTranslator.isAlreadyInScript("斗破苍穹", "zh"))
        assertFalse(SearchQueryTranslator.isAlreadyInScript("battle through the heavens", "zh"))
        assertTrue(SearchQueryTranslator.isAlreadyInScript("転生したらスライム", "ja"))
        assertFalse(SearchQueryTranslator.isAlreadyInScript("slime isekai", "ja"))
        assertTrue(SearchQueryTranslator.isAlreadyInScript("전지적 독자 시점", "ko"))
        assertFalse(SearchQueryTranslator.isAlreadyInScript("omniscient reader", "ko"))
    }

    @Test
    fun untranslatableLanguageIsTreatedAsNoOp() {
        assertTrue(SearchQueryTranslator.isAlreadyInScript("anything", "en"))
    }
}
