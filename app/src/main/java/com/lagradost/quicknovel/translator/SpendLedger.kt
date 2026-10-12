package com.lagradost.quicknovel.translator

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.db.SpendEntry
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException

/**
 * The spend ledger: every paid LLM call (via [UsageSinks.global], installed at app start) and
 * every chapter of Batch API results ([recordBatchChapter]) is a row in `spend_entries`.
 */
object SpendLedger {
    private const val TAG = "SpendLedger"

    data class Summary(
        val todayUsd: Double,
        val monthUsd: Double,
        val allTimeUsd: Double,
        val byNovel: List<SpendRow>,
        val byModel: List<SpendRow>,
        val latest: List<SpendEntry>,
        /** Calls whose model had no known price (counted as $0). */
        val unpricedCalls: Int,
        val monthlyCapUsd: Double,
        val perJobCapUsd: Double,
    )

    /** Installs the Room-backed sink for every [TranslationEngine]. Safe to call repeatedly. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        UsageSinks.global = UsageSink { event -> insert(appContext, event) }
    }

    private suspend fun insert(ctx: Context, event: UsageEvent) {
        try {
            TranslatorDatabase.getInstance(ctx).spendDao().insert(
                SpendEntry(
                    timestamp = event.timestamp,
                    novelId = event.novelId,
                    chapterNum = event.chapterNum,
                    kind = event.kind.id,
                    provider = event.providerId,
                    model = event.model,
                    promptTokens = event.promptTokens,
                    completionTokens = event.completionTokens,
                    usd = event.usd,
                    priced = event.priced,
                    batchId = event.batchId,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record spend", e)
        }
    }

    /** One chapter's share of a batch (tokens from the results' usage, at the batch discount). */
    suspend fun recordBatchChapter(
        ctx: Context,
        batchId: Long,
        novelId: String,
        chapterNum: Int,
        providerId: String,
        model: String,
        usage: TokenUsage,
        usd: Double,
        priced: Boolean,
    ) = insert(
        ctx,
        UsageEvent(
            timestamp = System.currentTimeMillis(),
            kind = SpendKind.BATCH,
            novelId = novelId,
            chapterNum = chapterNum,
            providerId = providerId,
            model = model,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            usd = usd,
            priced = priced,
            batchId = batchId,
        ),
    )

    /** USD spent since the start of the current month (local time); 0 if the DB cannot be read. */
    suspend fun monthTotal(ctx: Context, now: Long = System.currentTimeMillis()): Double = try {
        TranslatorDatabase.getInstance(ctx).spendDao().totalSince(SpendPeriods.startOfMonth(now))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the month's spend", e)
        0.0
    }

    suspend fun summary(ctx: Context, now: Long = System.currentTimeMillis()): Summary {
        val dao = TranslatorDatabase.getInstance(ctx).spendDao()
        val byNovel = dao.totalsByNovel().map { SpendRow(it.name ?: "No novel (search, verify…)", it.usd, it.calls) }
        val byModel = dao.totalsByModel().map { SpendRow(it.name ?: "?", it.usd, it.calls) }
        return Summary(
            todayUsd = dao.totalSince(SpendPeriods.startOfDay(now)),
            monthUsd = dao.totalSince(SpendPeriods.startOfMonth(now)),
            allTimeUsd = dao.totalSince(0),
            byNovel = SpendBreakdown.topWithRest(byNovel, 10),
            byModel = byModel,
            latest = dao.latest(50),
            unpricedCalls = dao.countUnpriced(),
            monthlyCapUsd = SecurePrefs.getMonthlySpendCap(ctx),
            perJobCapUsd = SecurePrefs.getPerJobCap(ctx),
        )
    }

    suspend fun clear(ctx: Context) = TranslatorDatabase.getInstance(ctx).spendDao().clear()

    /** Start check for a job estimated at [estimateUsd] (null = unknown price) against the caps. */
    suspend fun checkStart(ctx: Context, estimateUsd: Double?): SpendCaps.StartCheck =
        SecurePrefs.spendCaps(ctx).checkStart(estimateUsd, monthTotal(ctx))

    /** Why a running job with [meter] must stop now, with the message for the notification; null to go on. */
    suspend fun stopMessage(ctx: Context, meter: SpendMeter?): String? {
        val caps = SecurePrefs.spendCaps(ctx)
        if (!caps.monthlyOn && !caps.perJobOn) return null
        val month = monthTotal(ctx)
        val job = meter?.usd ?: 0.0
        val reason = caps.stopReason(month, job) ?: return null
        return caps.stopMessage(reason, month, job)
    }
}
