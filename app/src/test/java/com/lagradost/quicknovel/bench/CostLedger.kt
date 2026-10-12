package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.time.Instant

class BudgetExceededException(message: String) : RuntimeException(message)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LedgerEntry(
    val time: String,
    val runId: String,
    val purpose: String, // smoke | bible | translate | judge
    val model: String,
    val sample: String = "",
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val usd: Double = 0.0,
    val httpStatus: Int = 200,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LedgerFile(val totalUsd: Double = 0.0, val entries: List<LedgerEntry> = emptyList())

/**
 * Persistent cost ledger shared by all runs (app/build/translation-bench/ledger.json).
 *
 * Every API call first [reserve]s its estimated cost; the reservation is refused when
 * (spent across ALL runs) + (outstanding reservations) + estimate > budget. After the call
 * the reservation is replaced by the actual cost from the response `usage`.
 */
class CostLedger(private val file: File, val budgetUsd: Double, val runId: String) {
    private val entries = mutableListOf<LedgerEntry>()
    private var reserved = 0.0
    private var nextReservation = 0L
    private val reservations = mutableMapOf<Long, Double>()

    init {
        if (file.isFile) {
            runCatching { benchMapper.readValue<LedgerFile>(file) }.getOrNull()?.let { entries.addAll(it.entries) }
        }
    }

    @get:Synchronized
    val totalUsd: Double get() = entries.sumOf { it.usd }

    @get:Synchronized
    val runUsd: Double get() = entries.filter { it.runId == runId }.sumOf { it.usd }

    @Synchronized
    fun runEntries(): List<LedgerEntry> = entries.filter { it.runId == runId }

    @Synchronized
    fun remaining(): Double = budgetUsd - totalUsd - reserved

    /** @return a reservation id, or throws [BudgetExceededException]. */
    @Synchronized
    fun reserve(estimateUsd: Double, what: String): Long {
        val spent = totalUsd
        if (spent + reserved + estimateUsd > budgetUsd) {
            throw BudgetExceededException(
                "Budget cap reached: spent \$%.4f + in-flight \$%.4f + next estimate \$%.4f (%s) > cap \$%.2f"
                    .format(spent, reserved, estimateUsd, what, budgetUsd)
            )
        }
        val id = nextReservation++
        reservations[id] = estimateUsd
        reserved += estimateUsd
        return id
    }

    @Synchronized
    fun commit(reservation: Long, entry: LedgerEntry) {
        release(reservation)
        entries.add(entry)
        persist()
    }

    @Synchronized
    fun release(reservation: Long) {
        reservations.remove(reservation)?.let { reserved -= it }
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        benchMapper.writeValue(tmp, LedgerFile(entries.sumOf { it.usd }, entries))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun entry(
        purpose: String, model: String, sample: String,
        prompt: Long, completion: Long, reasoning: Long, usd: Double, status: Int = 200
    ) = LedgerEntry(Instant.now().toString(), runId, purpose, model, sample, prompt, completion, reasoning, usd, status)
}

/** Rough pre-call token estimates (only used for budget reservations). */
object TokenEstimate {
    /** ~1 token per CJK/Hangul char, ~4 ASCII chars per token. */
    fun tokens(text: String): Long {
        var t = 0.0
        for (c in text) t += if (c.code >= 0x2E80) 1.0 else 0.27
        return t.toLong() + 8
    }

    /** CJK characters in [text] (approximate source length inside a prompt). */
    fun cjkChars(text: String): Int = text.count { it.code >= 0x2E80 }

    fun reasoningAllowance(effort: String?): Long = when (effort) {
        null -> 0
        "none" -> 0
        "minimal" -> 300
        "low" -> 1500
        else -> 4000
    }
}
