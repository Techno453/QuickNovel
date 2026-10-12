package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TitleLookupModelTest {
    @Test
    fun defaultIsGpt6Sol() {
        assertEquals("gpt-6-sol", TitleLookupModel.DEFAULT_PRESET_ID)
        assertNotNull(ModelPresets.byId(TitleLookupModel.DEFAULT_PRESET_ID))
        assertEquals("gpt-6-sol", TitleLookupModel.presetId(null))
    }

    @Test
    fun sameAsTranslationModelIsNull() {
        assertNull(TitleLookupModel.presetId(TitleLookupModel.SAME_AS_TRANSLATION))
        assertEquals(TitleLookupModel.SAME_AS_TRANSLATION, TitleLookupModel.storedValue(null))
    }

    @Test
    fun chosenPresetRoundTripsAndUnknownFallsBackToTheDefault() {
        val luna = ModelPresets.GPT_6_LUNA.id
        assertEquals(luna, TitleLookupModel.presetId(TitleLookupModel.storedValue(luna)))
        assertEquals(TitleLookupModel.DEFAULT_PRESET_ID, TitleLookupModel.presetId("no-such-model"))
        assertEquals(TitleLookupModel.SAME_AS_TRANSLATION, TitleLookupModel.storedValue("no-such-model"))
    }
}
