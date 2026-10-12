package com.lagradost.quicknovel.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalNoticeTest {
    private fun reason(translatable: Boolean = true, onPurpose: Boolean = false, onDemand: Boolean, hasKey: Boolean) =
        OriginalNotice.reasonFor(translatable, onPurpose, onDemand, hasKey)

    @Test
    fun switchOffSaysSo() {
        assertEquals(OriginalNotice.TRANSLATION_OFF, reason(onDemand = false, hasKey = true))
        // Same order as getOrTranslate: the switch is checked before the key.
        assertEquals(OriginalNotice.TRANSLATION_OFF, reason(onDemand = false, hasKey = false))
    }

    @Test
    fun missingKeyWithTheSwitchOn() {
        assertEquals(OriginalNotice.NO_API_KEY, reason(onDemand = true, hasKey = false))
    }

    @Test
    fun noNoticeWhenTranslationCouldRunOrWasNotWanted() {
        // switch on and a key: the translation failed or is not there for another reason
        assertNull(reason(onDemand = true, hasKey = true))
        // Reload / Show original: the original on purpose
        assertNull(reason(onPurpose = true, onDemand = false, hasKey = false))
        // not a zh / ja / ko source, or an import without a known language
        assertNull(reason(translatable = false, onDemand = false, hasKey = false))
    }
}
