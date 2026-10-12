package com.lagradost.quicknovel.translator

/**
 * Spend caps (pure arithmetic, unit-tested). A cap of 0 (or less) is off.
 *
 * - Start of a range / batch job: the job's estimate plus this month's spend must fit the
 *   monthly cap, and the estimate must fit the per-job cap.
 * - During a job: stop once this month's spend reaches the monthly cap or the job's own spend
 *   reaches the per-job cap ("reaches" = at or above).
 */
data class SpendCaps(
    val monthlyUsd: Double = 0.0,
    val perJobUsd: Double = 0.0,
) {
    val monthlyOn: Boolean get() = monthlyUsd > 0
    val perJobOn: Boolean get() = perJobUsd > 0

    sealed class StartCheck {
        object Allowed : StartCheck()

        /** [spentUsd] this month + [estimateUsd] would pass [capUsd]. */
        data class MonthlyCapExceeded(val estimateUsd: Double, val spentUsd: Double, val capUsd: Double) : StartCheck()

        data class PerJobCapExceeded(val estimateUsd: Double, val capUsd: Double) : StartCheck()

        val allowed: Boolean get() = this is Allowed

        /** User-facing explanation that names the numbers; null when allowed. */
        val message: String?
            get() = when (this) {
                Allowed -> null
                is MonthlyCapExceeded ->
                    if (estimateUsd <= 0.0) {
                        "Monthly spend cap reached: ${SpendPricing.format(spentUsd)} spent this month of the " +
                            "${SpendPricing.format(capUsd)} cap."
                    } else {
                        "This job would pass the monthly spend cap: ${SpendPricing.format(spentUsd)} spent this month + " +
                            "about ${SpendPricing.format(estimateUsd)} for this job = " +
                            "${SpendPricing.format(spentUsd + estimateUsd)}, cap ${SpendPricing.format(capUsd)}. " +
                            "Translate fewer chapters or raise the cap in Settings → AI Translation."
                    }
                is PerJobCapExceeded ->
                    "This job's estimate (about ${SpendPricing.format(estimateUsd)}) is over the per-job cap of " +
                        "${SpendPricing.format(capUsd)}. Translate fewer chapters or raise the cap."
            }
    }

    enum class StopReason { MONTHLY_CAP, PER_JOB_CAP }

    /**
     * @param estimateUsd the job's estimated cost; null when the model's price is unknown (then only
     *   an already exhausted monthly cap refuses the job)
     */
    fun checkStart(estimateUsd: Double?, monthSpentUsd: Double): StartCheck {
        val estimate = (estimateUsd ?: 0.0).coerceAtLeast(0.0)
        val spent = monthSpentUsd.coerceAtLeast(0.0)
        if (monthlyOn && (spent >= monthlyUsd || spent + estimate > monthlyUsd + EPSILON)) {
            return StartCheck.MonthlyCapExceeded(estimate, spent, monthlyUsd)
        }
        if (perJobOn && estimate > perJobUsd + EPSILON) return StartCheck.PerJobCapExceeded(estimate, perJobUsd)
        return StartCheck.Allowed
    }

    /** Why a running job must stop now, or null to carry on. */
    fun stopReason(monthSpentUsd: Double, jobSpentUsd: Double): StopReason? = when {
        monthlyOn && monthSpentUsd >= monthlyUsd -> StopReason.MONTHLY_CAP
        perJobOn && jobSpentUsd >= perJobUsd -> StopReason.PER_JOB_CAP
        else -> null
    }

    /** What is left of the monthly cap (never negative); null when the monthly cap is off. */
    fun remainingMonthly(monthSpentUsd: Double): Double? =
        if (monthlyOn) (monthlyUsd - monthSpentUsd).coerceAtLeast(0.0) else null

    /** Notification / progress text for a job stopped for [reason]. */
    fun stopMessage(reason: StopReason, monthSpentUsd: Double, jobSpentUsd: Double): String = when (reason) {
        StopReason.MONTHLY_CAP ->
            "Stopped: monthly spend cap of ${SpendPricing.format(monthlyUsd)} reached " +
                "(${SpendPricing.format(monthSpentUsd)} spent this month)."
        StopReason.PER_JOB_CAP ->
            "Stopped: per-job cap of ${SpendPricing.format(perJobUsd)} reached " +
                "(${SpendPricing.format(jobSpentUsd)} spent by this job)."
    }

    companion object {
        /** Rounding slack so an estimate exactly at the cap is allowed. */
        private const val EPSILON = 1e-9
    }
}
