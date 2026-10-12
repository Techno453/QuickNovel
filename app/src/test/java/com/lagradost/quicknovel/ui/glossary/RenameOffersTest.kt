package com.lagradost.quicknovel.ui.glossary

import com.lagradost.quicknovel.translator.db.GlossaryTerm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When saving a glossary edit offers to update the stored translations (any category, locked or not). */
class RenameOffersTest {
    private fun term(english: String, category: String = "term", locked: Boolean = false) =
        GlossaryTerm(novelId = "Quanben/高天之上", source = "伊安", english = english, category = category, locked = locked)

    @Test
    fun offersWhenTheEnglishRenderingChanges() {
        assertTrue(RenameOffers.shouldOffer(term("Ian"), term("Ean")))
        assertTrue(RenameOffers.shouldOffer(term("spirit stone", category = "item"), term("spirit crystal", category = "item")))
        assertTrue(RenameOffers.shouldOffer(term("Ian", locked = false), term("Ean", locked = false)))
    }

    @Test
    fun noOfferForNewTermsBlankOrUnchangedRenderings() {
        assertFalse(RenameOffers.shouldOffer(null, term("Ian")))
        assertFalse(RenameOffers.shouldOffer(term(""), term("Ian")))
        assertFalse(RenameOffers.shouldOffer(term("Ian"), term("")))
        assertFalse(RenameOffers.shouldOffer(term("Ian"), term("Ian ")))
        assertFalse(RenameOffers.shouldOffer(term("Ian"), term("Ian", category = "character", locked = true)))
    }
}
