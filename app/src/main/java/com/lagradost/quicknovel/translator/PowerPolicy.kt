package com.lagradost.quicknovel.translator

/** Battery state as the foreground service sees it. */
data class PowerState(
    /** Plugged in (AC, USB or wireless), or full while plugged. */
    val charging: Boolean,
    /** 0–100, or -1 when unknown. */
    val levelPercent: Int,
    /** The system's ACTION_BATTERY_LOW was received more recently than ACTION_BATTERY_OKAY. */
    val systemLow: Boolean = false,
) {
    companion object {
        /** Before the first reading: assume a job may run. */
        val UNKNOWN = PowerState(charging = true, levelPercent = -1)
    }
}

/**
 * When long background jobs (range jobs, read-ahead) may run. Pure; the service feeds it battery
 * broadcasts. On-demand translation of the chapter being read never asks.
 */
data class PowerPolicy(
    /** Run only while charging (`jobs_only_while_charging`, default off). */
    val onlyWhileCharging: Boolean = false,
    /** Pause on low battery (`jobs_pause_on_low_battery`, default on). */
    val pauseOnLowBattery: Boolean = true,
    /** Low-battery threshold in percent (`jobs_low_battery_percent`, default 15). */
    val lowBatteryPercent: Int = DEFAULT_LOW_BATTERY_PERCENT,
) {
    enum class Pause(val message: String) {
        WAITING_FOR_CHARGER("Paused: waiting for charger"),
        BATTERY_LOW("Paused: battery low"),
    }

    /**
     * Why a job must wait, or null to run. While charging, a low battery never pauses (it is
     * charging up). The system low-battery broadcast counts only when the level is unknown, so a
     * user threshold below the system's (about 15%) is respected.
     */
    fun pauseReason(state: PowerState): Pause? {
        if (onlyWhileCharging && !state.charging) return Pause.WAITING_FOR_CHARGER
        if (pauseOnLowBattery && !state.charging) {
            val low = if (state.levelPercent >= 0) state.levelPercent <= lowBatteryPercent else state.systemLow
            if (low) return Pause.BATTERY_LOW
        }
        return null
    }

    /** Same policy with "only while charging" forced on (the overnight option for non-OpenAI models). */
    fun requireCharging(): PowerPolicy = copy(onlyWhileCharging = true)

    companion object {
        const val DEFAULT_LOW_BATTERY_PERCENT = 15
        const val MIN_LOW_BATTERY_PERCENT = 5
        const val MAX_LOW_BATTERY_PERCENT = 50
    }
}
