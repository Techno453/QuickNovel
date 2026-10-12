package com.lagradost.quicknovel.ui.common

import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.ConnectionResult
import com.lagradost.quicknovel.translator.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wizard and Settings → API key → Test connection share one wording per result. */
class ConnectionMessagesTest {
    @Test
    fun everyStatusHasItsMessage() {
        assertEquals(R.string.setup_test_ok, ConnectionMessages.messageRes(ConnectionStatus.OK, 200))
        assertEquals(R.string.setup_test_ok, ConnectionMessages.messageRes(ConnectionStatus.OK, null))
        assertEquals(R.string.setup_test_ok_rate_limited, ConnectionMessages.messageRes(ConnectionStatus.OK, 429))
        assertEquals(R.string.setup_test_invalid_key, ConnectionMessages.messageRes(ConnectionStatus.INVALID_KEY, 401))
        assertEquals(R.string.setup_test_network, ConnectionMessages.messageRes(ConnectionStatus.NETWORK_ERROR, null))
        assertEquals(R.string.setup_test_blocked, ConnectionMessages.messageRes(ConnectionStatus.BLOCKED, 451))
        assertEquals(R.string.setup_test_provider_error, ConnectionMessages.messageRes(ConnectionStatus.PROVIDER_ERROR, 500))
    }

    @Test
    fun detailJoinsCodeAndMessage() {
        assertEquals(
            "HTTP 401 · Incorrect API key",
            ConnectionMessages.detail(ConnectionResult(ConnectionStatus.INVALID_KEY, 401, "Incorrect API key")),
        )
        assertEquals("HTTP 500", ConnectionMessages.detail(ConnectionResult(ConnectionStatus.PROVIDER_ERROR, 500, " ")))
        assertEquals("timeout", ConnectionMessages.detail(ConnectionResult(ConnectionStatus.NETWORK_ERROR, null, "timeout")))
        assertEquals("", ConnectionMessages.detail(ConnectionResult(ConnectionStatus.OK)))
    }
}
