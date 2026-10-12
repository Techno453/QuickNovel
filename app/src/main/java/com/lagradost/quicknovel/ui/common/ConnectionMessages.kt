package com.lagradost.quicknovel.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.ConnectionResult
import com.lagradost.quicknovel.translator.ConnectionStatus

/**
 * One wording for the result of the free connection test ([com.lagradost.quicknovel.translator.ConnectionTester]),
 * used by the setup wizard and by Settings → AI translation → API key → Test connection.
 */
object ConnectionMessages {
    /** The headline for a result; the INVALID_KEY / NETWORK / BLOCKED / PROVIDER_ERROR texts take the provider name. */
    @StringRes
    fun messageRes(status: ConnectionStatus, httpCode: Int?): Int = when (status) {
        ConnectionStatus.OK -> if (httpCode == 429) R.string.setup_test_ok_rate_limited else R.string.setup_test_ok
        ConnectionStatus.INVALID_KEY -> R.string.setup_test_invalid_key
        ConnectionStatus.NETWORK_ERROR -> R.string.setup_test_network
        ConnectionStatus.BLOCKED -> R.string.setup_test_blocked
        ConnectionStatus.PROVIDER_ERROR -> R.string.setup_test_provider_error
    }

    /** "HTTP 401 · Incorrect API key" or "" (shown only for failures). */
    fun detail(result: ConnectionResult): String =
        listOfNotNull(result.httpCode?.let { "HTTP $it" }, result.detail.takeIf { it.isNotBlank() })
            .joinToString(" · ")

    fun message(context: Context, result: ConnectionResult, providerLabel: String): String =
        context.getString(messageRes(result.status, result.httpCode), providerLabel)

    /** Headline plus "Details: …" for a failure; the headline alone when the key works. */
    fun messageWithDetail(context: Context, result: ConnectionResult, providerLabel: String): String {
        val message = message(context, result, providerLabel)
        val detail = detail(result)
        return if (result.status == ConnectionStatus.OK || detail.isBlank()) message
        else context.getString(R.string.tr_test_toast, message, context.getString(R.string.setup_test_detail, detail))
    }
}
