package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendCapsTest {
    @Test
    fun capsOffAllowEverything() {
        val caps = SpendCaps()
        assertEquals(SpendCaps.StartCheck.Allowed, caps.checkStart(1_000.0, 1_000.0))
        assertNull(caps.stopReason(1_000.0, 1_000.0))
        assertNull(caps.remainingMonthly(3.0))
    }

    @Test
    fun monthlyCapRefusesWhenEstimatePlusSpentExceedsIt() {
        val caps = SpendCaps(monthlyUsd = 5.0)
        assertEquals(SpendCaps.StartCheck.Allowed, caps.checkStart(0.40, 4.50))
        assertEquals(SpendCaps.StartCheck.Allowed, caps.checkStart(0.50, 4.50)) // exactly at the cap
        val refused = caps.checkStart(0.60, 4.50)
        assertEquals(SpendCaps.StartCheck.MonthlyCapExceeded(0.60, 4.50, 5.0), refused)
        val message = refused.message!!
        assertTrue(message, message.contains("\$4.50") && message.contains("\$0.60") && message.contains("\$5.10") && message.contains("\$5.00"))
    }

    @Test
    fun exhaustedMonthlyCapRefusesEvenUnknownPrices() {
        val caps = SpendCaps(monthlyUsd = 2.0)
        assertEquals(SpendCaps.StartCheck.Allowed, caps.checkStart(null, 1.99))
        val refused = caps.checkStart(null, 2.0)
        assertTrue(refused is SpendCaps.StartCheck.MonthlyCapExceeded)
        assertTrue(refused.message!!.contains("cap reached"))
    }

    @Test
    fun perJobCapRefusesALargerEstimate() {
        val caps = SpendCaps(perJobUsd = 1.0)
        assertEquals(SpendCaps.StartCheck.Allowed, caps.checkStart(1.0, 50.0))
        val refused = caps.checkStart(1.25, 0.0)
        assertEquals(SpendCaps.StartCheck.PerJobCapExceeded(1.25, 1.0), refused)
        assertTrue(refused.message!!.contains("\$1.25") && refused.message!!.contains("\$1.00"))
    }

    @Test
    fun monthlyCapIsCheckedBeforeThePerJobCap() {
        val caps = SpendCaps(monthlyUsd = 1.0, perJobUsd = 0.5)
        assertTrue(caps.checkStart(0.8, 0.5) is SpendCaps.StartCheck.MonthlyCapExceeded)
        assertTrue(caps.checkStart(0.6, 0.0) is SpendCaps.StartCheck.PerJobCapExceeded)
    }

    @Test
    fun runningJobStopsWhenACapIsReached() {
        val caps = SpendCaps(monthlyUsd = 3.0, perJobUsd = 0.25)
        assertNull(caps.stopReason(2.99, 0.24))
        assertEquals(SpendCaps.StopReason.MONTHLY_CAP, caps.stopReason(3.0, 0.0))
        assertEquals(SpendCaps.StopReason.PER_JOB_CAP, caps.stopReason(1.0, 0.25))
        assertEquals(SpendCaps.StopReason.MONTHLY_CAP, caps.stopReason(3.5, 0.5))
        val msg = caps.stopMessage(SpendCaps.StopReason.MONTHLY_CAP, 3.02, 0.1)
        assertTrue(msg, msg.contains("\$3.00") && msg.contains("\$3.02"))
        assertEquals(0.5, caps.remainingMonthly(2.5)!!, 1e-9)
        assertEquals(0.0, caps.remainingMonthly(4.0)!!, 1e-9)
    }
}
