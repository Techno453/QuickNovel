package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/** The encrypted-or-plain decision behind [SecurePrefs.isUsingPlainFallback]. */
class KeyStoreChoiceTest {
    @Test
    fun encryptedStoreIsUsedWhenItOpens() {
        var plainOpened = false
        val opened = KeyStoreChoice.open(openEncrypted = { "encrypted" }, openPlain = { plainOpened = true; "plain" })
        assertEquals("encrypted", opened.store)
        assertFalse(opened.plainFallback)
        assertFalse("plain store must not be touched", plainOpened)
    }

    @Test
    fun brokenKeystoreFallsBackToPlainAndReportsIt() {
        val failure = KeyStoreException("Keystore unavailable")
        var reported: Throwable? = null
        val opened = KeyStoreChoice.open(
            openEncrypted = { throw failure },
            openPlain = { "plain" },
            onFallback = { reported = it },
        )
        assertEquals("plain", opened.store)
        assertTrue(opened.plainFallback)
        assertSame(failure, reported)
    }

    @Test
    fun errorsAreCaughtToo() {
        // Some devices throw Errors (e.g. from the keystore daemon) rather than exceptions.
        val opened = KeyStoreChoice.open<String>(openEncrypted = { throw IllegalStateException("bad master key") }, openPlain = { "plain" })
        assertTrue(opened.plainFallback)
        val fromError = KeyStoreChoice.open<String>(openEncrypted = { throw ExceptionInInitializerError("tink") }, openPlain = { "plain" })
        assertTrue(fromError.plainFallback)
    }
}
