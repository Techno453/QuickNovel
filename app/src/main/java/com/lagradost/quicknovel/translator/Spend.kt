package com.lagradost.quicknovel.translator

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** What a paid call was for (stored as [id] in `spend_entries.kind`). */
enum class SpendKind(val id: String, val label: String) {
    TRANSLATE("translate", "Translation"),
    ANALYSIS("analysis", "Analysis"),
    READ_AHEAD("read_ahead", "Read-ahead"),
    BATCH("batch", "Overnight batch"),
    SEARCH("search", "Search"),
    OTHER("other", "Other");

    companion object {
        fun fromId(id: String?): SpendKind = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/**
 * Running USD total of the calls made under one [SpendTag] (a job), for the per-job cap.
 * Thread-safe; amounts are kept in micro-dollars to avoid drift.
 */
class SpendMeter {
    private val micros = java.util.concurrent.atomic.AtomicLong(0)

    val usd: Double get() = micros.get() / 1_000_000.0

    fun add(usd: Double) {
        if (usd > 0) micros.addAndGet(Math.round(usd * 1_000_000))
    }
}

/**
 * Coroutine context element naming what the LLM calls made inside it are for. The engine reads
 * it when it reports usage; calls without a tag are recorded as [SpendKind.OTHER].
 *
 * ```
 * withContext(SpendTag(SpendKind.READ_AHEAD, novelId, chapter)) { engine.translateChapter(...) }
 * ```
 */
data class SpendTag(
    val kind: SpendKind,
    val novelId: String? = null,
    val chapterNum: Int? = null,
    /** Local `batch_jobs.id` for batch results and their live re-translations. */
    val batchId: Long? = null,
    /** Accumulates the cost of every call made under this tag (per-job cap). */
    val meter: SpendMeter? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpendTag>

    /** Same job (meter, batch), different chapter. */
    fun forChapter(chapter: Int?): SpendTag = copy(chapterNum = chapter)
}

/** One paid call (or one chapter of batch results), as recorded in the spend ledger. */
data class UsageEvent(
    val timestamp: Long,
    val kind: SpendKind,
    val novelId: String?,
    val chapterNum: Int?,
    val providerId: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val usd: Double,
    /** False when the model has no known price (custom / unknown model); [usd] is then 0. */
    val priced: Boolean,
    val batchId: Long? = null,
)

/** Receives every paid call's usage. Implementations must not throw. */
fun interface UsageSink {
    suspend fun record(event: UsageEvent)
}

/** The process-wide sink (the app installs the Room-backed ledger; plain JVM code has none). */
object UsageSinks {
    @Volatile
    var global: UsageSink? = null
}

/** Token usage of one response (`usage.prompt_tokens` / `usage.completion_tokens`). */
data class TokenUsage(val promptTokens: Int, val completionTokens: Int) {
    operator fun plus(other: TokenUsage) =
        TokenUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens)

    companion object {
        val ZERO = TokenUsage(0, 0)
    }
}

object SpendPricing {
    /**
     * OpenAI Batch API discount: batch requests cost 50% of the list price, input and output.
     * Checked 2026-10-11 against developers.openai.com/api/docs/pricing for gpt-6-luna
     * (0.10/0.50 → 0.05/0.25), gpt-6-sol, gpt-5.4-mini, gpt-4o-mini and gpt-4o.
     */
    const val BATCH_DISCOUNT = 0.5

    /**
     * Price per token for [config]: the preset's own prices when it came from a preset, else the
     * built-in price table for the model id (custom models have no user-entered price), else null.
     */
    fun pricingFor(config: EngineConfig): CostEstimator.ModelPricing? =
        ModelPresets.byId(config.presetId)?.let { CostEstimator.ModelPricing(it.priceIn, it.priceOut) }
            ?: CostEstimator.pricingFor(config.model)

    /** USD for [usage] at [pricing] × [factor] (0 when unpriced). */
    fun usd(usage: TokenUsage, pricing: CostEstimator.ModelPricing?, factor: Double = 1.0): Double {
        pricing ?: return 0.0
        return (usage.promptTokens / 1_000_000.0 * pricing.inputPerMillion +
            usage.completionTokens / 1_000_000.0 * pricing.outputPerMillion) * factor
    }

    fun format(usd: Double): String = when {
        usd <= 0.0 -> "\$0.00"
        usd < 0.01 -> "\$" + String.format(Locale.US, "%.4f", usd)
        else -> "\$" + String.format(Locale.US, "%.2f", usd)
    }
}

/** A row of a spend breakdown (per novel or per model). */
data class SpendRow(val name: String, val usd: Double, val calls: Int)

object SpendBreakdown {
    /** The [top] largest rows (by USD) plus one "rest" row summing the others, if there are more. */
    fun topWithRest(rows: List<SpendRow>, top: Int, restLabel: String = "Everything else"): List<SpendRow> {
        val sorted = rows.sortedByDescending { it.usd }
        if (sorted.size <= top) return sorted
        val rest = sorted.drop(top)
        return sorted.take(top) + SpendRow("$restLabel (${rest.size})", rest.sumOf { it.usd }, rest.sumOf { it.calls })
    }
}

/** Calendar boundaries for "today" and "this month" (local time). */
object SpendPeriods {
    fun startOfDay(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun startOfMonth(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = startOfDay(nowMs, zone)
            set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
}
