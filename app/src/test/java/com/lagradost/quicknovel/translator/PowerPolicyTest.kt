package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerPolicyTest {
    private val plugged = PowerState(charging = true, levelPercent = 40)
    private val onBattery = PowerState(charging = false, levelPercent = 40)
    private val lowOnBattery = PowerState(charging = false, levelPercent = 15)

    @Test
    fun defaultsPauseOnlyOnLowBattery() {
        val p = PowerPolicy()
        assertNull(p.pauseReason(plugged))
        assertNull(p.pauseReason(onBattery))
        assertEquals(PowerPolicy.Pause.BATTERY_LOW, p.pauseReason(lowOnBattery))
        assertNull(p.pauseReason(PowerState(charging = false, levelPercent = 16)))
    }

    @Test
    fun lowBatteryNeverPausesWhileCharging() {
        assertNull(PowerPolicy().pauseReason(PowerState(charging = true, levelPercent = 3)))
    }

    @Test
    fun onlyWhileChargingWaitsForTheCharger() {
        val p = PowerPolicy(onlyWhileCharging = true)
        assertEquals(PowerPolicy.Pause.WAITING_FOR_CHARGER, p.pauseReason(onBattery))
        assertEquals(PowerPolicy.Pause.WAITING_FOR_CHARGER, p.pauseReason(lowOnBattery))
        assertNull(p.pauseReason(plugged))
        assertEquals(PowerPolicy.Pause.WAITING_FOR_CHARGER, PowerPolicy().requireCharging().pauseReason(onBattery))
    }

    @Test
    fun thresholdAndSwitchAreRespected() {
        assertNull(PowerPolicy(pauseOnLowBattery = false).pauseReason(lowOnBattery))
        assertEquals(PowerPolicy.Pause.BATTERY_LOW, PowerPolicy(lowBatteryPercent = 30).pauseReason(PowerState(false, 30)))
        assertNull(PowerPolicy(lowBatteryPercent = 10).pauseReason(PowerState(false, 12, systemLow = true)))
    }

    @Test
    fun systemLowBroadcastCountsOnlyWhenTheLevelIsUnknown() {
        assertEquals(PowerPolicy.Pause.BATTERY_LOW, PowerPolicy().pauseReason(PowerState(false, -1, systemLow = true)))
        assertNull(PowerPolicy().pauseReason(PowerState(false, -1, systemLow = false)))
        assertNull(PowerPolicy().pauseReason(PowerState.UNKNOWN))
    }

    @Test
    fun messagesMatchTheNotificationText() {
        assertEquals("Paused: waiting for charger", PowerPolicy.Pause.WAITING_FOR_CHARGER.message)
        assertEquals("Paused: battery low", PowerPolicy.Pause.BATTERY_LOW.message)
    }
}
