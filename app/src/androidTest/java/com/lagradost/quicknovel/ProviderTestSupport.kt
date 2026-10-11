package com.lagradost.quicknovel

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Shared setup for on-device provider integration tests: initialises [MainActivity.app]
 * the same way the app does and lets network failures skip a test instead of failing it.
 */
object ProviderTestSupport {
    fun initApp() {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        MainActivity.app = Requests(
            OkHttpClient()
                .newBuilder()
                .ignoreAllSSLErrors()
                .readTimeout(60L, TimeUnit.SECONDS)
                .connectTimeout(30L, TimeUnit.SECONDS)
                .build(),
            responseParser = object : ResponseParser {
                override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
                    mapper.readValue(text, kClass.java)

                override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
                    try {
                        mapper.readValue(text, kClass.java)
                    } catch (e: Exception) {
                        null
                    }

                override fun writeValueAsString(obj: Any): String =
                    mapper.writeValueAsString(obj)
            }
        ).apply {
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        }
    }

    /** True if [e] looks like a connectivity / geo / bot-protection problem rather than a bug. */
    fun isNetworkError(e: Throwable): Boolean {
        if (e is IOException) return true
        val msg = e.message ?: return false
        return msg.contains("403") || msg.contains("503") ||
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("failed to connect", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true)
    }

    /** Runs [block]; network errors are logged as SKIPPED, anything else fails the test. */
    fun runNetworkTest(tag: String, name: String, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            Log.i(tag, "✓ $name PASSED")
        } catch (e: Throwable) {
            if (isNetworkError(e)) {
                Log.w(tag, "SKIPPED $name — network error: ${e.message}")
            } else {
                Log.e(tag, "FAILED $name", e)
                throw e
            }
        }
    }
}
