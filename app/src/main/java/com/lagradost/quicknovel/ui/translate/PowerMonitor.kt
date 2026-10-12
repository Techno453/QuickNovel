package com.lagradost.quicknovel.ui.translate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.translator.PowerPolicy
import com.lagradost.quicknovel.translator.PowerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Battery / charger state for the translation service: reads the sticky ACTION_BATTERY_CHANGED
 * on [start] and follows ACTION_POWER_CONNECTED / DISCONNECTED, ACTION_BATTERY_LOW / OKAY and
 * battery level changes while registered.
 */
class PowerMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(PowerState.UNKNOWN)
    val state: StateFlow<PowerState> = _state.asStateFlow()

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = _state.value
            _state.value = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> current.copy(charging = true)
                Intent.ACTION_POWER_DISCONNECTED -> current.copy(charging = false)
                Intent.ACTION_BATTERY_LOW -> current.copy(systemLow = true)
                Intent.ACTION_BATTERY_OKAY -> current.copy(systemLow = false)
                Intent.ACTION_BATTERY_CHANGED -> fromBatteryChanged(intent, current)
                else -> current
            }
        }
    }

    private fun fromBatteryChanged(intent: Intent, current: PowerState): PowerState {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return current.copy(charging = charging, levelPercent = percent)
    }

    fun start() {
        if (registered) return
        try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                _state.value = fromBatteryChanged(it, _state.value.copy(systemLow = false))
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            // System broadcasts are delivered to non-exported receivers.
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the battery; jobs run unconditionally", e)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister the battery receiver", e)
        }
    }

    /**
     * Suspends while [policy] says to pause; [onPaused] is called with each new reason and
     * [onResumed] once when the job may go on (only if it had to wait). The policy is re-read
     * every minute so settings changes apply to a waiting job.
     */
    suspend fun awaitAllowed(
        policy: () -> PowerPolicy,
        onPaused: (PowerPolicy.Pause) -> Unit,
        onResumed: () -> Unit = {},
    ) {
        var lastReason: PowerPolicy.Pause? = null
        while (true) {
            val current = _state.value
            val reason = policy().pauseReason(current) ?: break
            if (reason != lastReason) {
                lastReason = reason
                onPaused(reason)
            }
            withTimeoutOrNull(RECHECK_MS) { _state.first { it != current } }
        }
        if (lastReason != null) onResumed()
    }

    companion object {
        private const val TAG = "PowerMonitor"
        private const val RECHECK_MS = 60_000L
    }
}
