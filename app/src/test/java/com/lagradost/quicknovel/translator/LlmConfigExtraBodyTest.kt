package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConfigExtraBodyTest {
    @Test
    fun parsesNestedObjectsAndNulls() {
        val body = LlmConfig.parseExtraBody("""{"reasoning":{"enabled":false},"temperature":null,"reasoning_effort":"none"}""")
        assertEquals(mapOf("enabled" to false), body["reasoning"])
        assertTrue(body.containsKey("temperature"))
        assertEquals(null, body["temperature"])
        assertEquals("none", body["reasoning_effort"])
    }

    @Test
    fun blankOrInvalidIsEmpty() {
        assertEquals(emptyMap<String, Any?>(), LlmConfig.parseExtraBody(null))
        assertEquals(emptyMap<String, Any?>(), LlmConfig.parseExtraBody("  "))
        assertEquals(emptyMap<String, Any?>(), LlmConfig.parseExtraBody("[1,2]"))
        assertEquals(emptyMap<String, Any?>(), LlmConfig.parseExtraBody("{oops"))
    }
}
