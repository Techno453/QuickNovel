package com.lagradost.quicknovel.ui.reader

import com.lagradost.quicknovel.translator.TermAliases
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlossaryLookupTest {
    private fun term(
        source: String,
        english: String,
        category: String = "character",
        aliases: List<String> = emptyList(),
        id: Long = 0,
    ) = GlossaryTerm(
        id = id, novelId = "n", source = source, english = english, category = category,
        aliases = TermAliases.encode(aliases),
    )

    private val lilia = term("莉莉娅", "Lilia", id = 1)
    private val linFeng = term("林枫", "Lin Feng", id = 2)
    private val lin = term("林", "Lin", id = 3)
    private val sect = term("青云宗", "Azure Cloud Sect", category = "sect", aliases = listOf("青云门"), id = 4)
    private val rose = term("罗丝", "Lilia Rose", id = 5)
    private val oBrien = term("奥布莱恩", "O'Brien", id = 6)
    private val terms = listOf(lilia, linFeng, lin, sect, oBrien)

    private fun at(text: String, word: String, inWord: Int = 0) = text.indexOf(word) + inWord

    @Test
    fun pressOnPlainName() {
        val text = "Then Lilia looked up."
        val m = GlossaryLookup.findAt(text, at(text, "Lilia", 2), terms)!!
        assertEquals(lilia, m.term)
        assertEquals("Lilia", text.substring(m.start, m.end))
    }

    @Test
    fun caseInsensitive() {
        val text = "LILIA! shouted the guard."
        assertEquals(lilia, GlossaryLookup.findAt(text, 1, terms)?.term)
    }

    @Test
    fun possessiveWithStraightAndCurlyApostrophe() {
        for (text in listOf("It was Lilia's sword.", "It was Lilia’s sword.")) {
            val start = text.indexOf("Lilia")
            // on the name
            val m = GlossaryLookup.findAt(text, start + 1, terms)!!
            assertEquals(lilia, m.term)
            assertEquals("Lilia", text.substring(m.start, m.end))
            // on the "s" of the possessive
            assertEquals(lilia, GlossaryLookup.findAt(text, start + 6, terms)?.term)
            // selection including the possessive
            assertEquals(lilia, GlossaryLookup.findForSelection(text, start, start + 7, terms)?.term)
        }
    }

    @Test
    fun apostropheInsideName() {
        val text = "O'Brien's ship left."
        val m = GlossaryLookup.findAt(text, 3, terms)!!
        assertEquals(oBrien, m.term)
        assertEquals("O'Brien", text.substring(m.start, m.end))
    }

    @Test
    fun multiWordNameIsExpandedAndPreferredOverShorterTerm() {
        val text = "\"Wait,\" said Lin Feng quietly."
        // pressing "Feng" or "Lin" both give the full name, not the shorter "Lin"
        for (offset in listOf(at(text, "Feng", 1), at(text, "Lin", 0))) {
            val m = GlossaryLookup.findAt(text, offset, terms)!!
            assertEquals(linFeng, m.term)
            assertEquals("Lin Feng", text.substring(m.start, m.end))
        }
        // selecting only "Feng" also expands to the full rendering
        val s = at(text, "Feng")
        val sel = GlossaryLookup.findForSelection(text, s, s + 4, terms)!!
        assertEquals(linFeng, sel.term)
        assertEquals("Lin Feng", text.substring(sel.start, sel.end))
    }

    @Test
    fun shortTermStillFoundAlone() {
        val text = "Lin nodded."
        assertEquals(lin, GlossaryLookup.findAt(text, 1, terms)?.term)
    }

    @Test
    fun wholeWordsOnly() {
        val text = "Linda smiled at Lilian."
        assertNull(GlossaryLookup.findAt(text, 1, terms))
        assertNull(GlossaryLookup.findAt(text, at(text, "Lilian", 2), terms))
    }

    @Test
    fun selectionContainingName() {
        val text = "They reached the Azure Cloud Sect gate at dawn."
        val s = at(text, "reached")
        val e = at(text, "gate") + 4
        val m = GlossaryLookup.findForSelection(text, s, e, terms)!!
        assertEquals(sect, m.term)
        assertEquals("Azure Cloud Sect", text.substring(m.start, m.end))
    }

    @Test
    fun sourceAndAliasInUntranslatedText() {
        val text = "林枫来到了青云门，莉莉娅在等他。"
        assertEquals(linFeng, GlossaryLookup.findAt(text, 1, terms)?.term)
        // alias of the sect
        val alias = GlossaryLookup.findAt(text, text.indexOf("青云门") + 2, terms)!!
        assertEquals(sect, alias.term)
        assertEquals("青云门", text.substring(alias.start, alias.end))
        assertEquals(lilia, GlossaryLookup.findAt(text, text.indexOf("莉莉娅"), terms)?.term)
    }

    @Test
    fun partOfMultiWordCharacterNameIsWeakMatch() {
        val text = "Lilia walked in."
        val m = GlossaryLookup.findAt(text, 2, listOf(rose))!!
        assertEquals(rose, m.term)
        assertFalse(m.exact)
        // possessive of a partial name, pressed on the "s"
        val poss = "Then Lilia's voice."
        val pm = GlossaryLookup.findAt(poss, poss.indexOf("'s") + 1, listOf(rose))!!
        assertEquals(rose, pm.term)
        assertEquals("Lilia", poss.substring(pm.start, pm.end))
        // an exact term wins over the partial one
        assertEquals(lilia, GlossaryLookup.findAt(text, 2, listOf(rose, lilia))?.term)
    }

    @Test
    fun noMatch() {
        val text = "The weather was fine."
        assertNull(GlossaryLookup.findAt(text, 5, terms))
        assertNull(GlossaryLookup.findForSelection(text, 4, 11, terms))
        assertNull(GlossaryLookup.findAt(text, 3, emptyList()))
        assertNull(GlossaryLookup.findAt("", 0, terms))
    }

    @Test
    fun englishForNewTermStripsPossessiveAndPunctuation() {
        val text = "\"Mira's blade!\""
        assertEquals("Mira", GlossaryLookup.englishForNewTerm(text, 0, 8))
        val w = GlossaryLookup.wordAt(text, 3)!!
        assertEquals("Mira", text.substring(w.first, w.last + 1))
        assertEquals("Grey Tower", GlossaryLookup.englishForNewTerm("(Grey Tower)", 0, 12))
    }

    @Test
    fun wordAtTakesKatakanaAndHangulRuns() {
        val jp = "ダンジョンの奥で"
        val w = GlossaryLookup.wordAt(jp, 2)!!
        assertEquals("ダンジョン", jp.substring(w.first, w.last + 1))
        val k = GlossaryLookup.wordAt(jp, 6)!!
        assertEquals("奥", jp.substring(k.first, k.last + 1))
        val ko = "그는 서울에 갔다"
        val h = GlossaryLookup.wordAt(ko, 4)!!
        assertEquals("서울에", ko.substring(h.first, h.last + 1))
    }

    @Test
    fun wordAtKeepsContractions() {
        val text = "He didn't go."
        val w = GlossaryLookup.wordAt(text, 5)!!
        assertEquals("didn't", text.substring(w.first, w.last + 1))
        assertNull(GlossaryLookup.wordAt("  ", 1))
    }

    @Test
    fun pendingSuggestionsForTerm() {
        val pending = listOf(
            PendingTerm(id = 1, novelId = "n", source = "莉莉娅", suggestedEnglish = "Lilya", notes = "conflicts with existing Lilia"),
            PendingTerm(id = 2, novelId = "n", source = "小莉", suggestedEnglish = "Lilia", notes = "same English as existing 莉莉娅 (alias?)"),
            PendingTerm(id = 3, novelId = "n", source = "青云门", suggestedEnglish = "Azure Cloud Gate"),
            PendingTerm(id = 4, novelId = "n", source = "王", suggestedEnglish = "Wang"),
            PendingTerm(id = 5, novelId = "other", source = "莉莉娅", suggestedEnglish = "Lilya"),
        )
        assertEquals(listOf(1L, 2L), GlossaryLookup.pendingFor(lilia, pending).map { it.id })
        assertEquals(listOf(3L), GlossaryLookup.pendingFor(sect, pending).map { it.id })
        assertNotNull(GlossaryLookup.pendingFor(linFeng, pending))
        assertEquals(0, GlossaryLookup.pendingFor(linFeng, pending).size)
    }
}
