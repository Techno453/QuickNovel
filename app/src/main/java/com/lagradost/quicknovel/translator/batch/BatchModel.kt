package com.lagradost.quicknovel.translator.batch

import com.lagradost.quicknovel.translator.TokenUsage

/**
 * Local state of a batch job (`batch_jobs.status`).
 *
 * PREPARING (fetching sources, uploading; local only) → VALIDATING → IN_PROGRESS → FINALIZING →
 * PROCESSING (results downloaded, being stored) → DONE. Side exits: FAILED (OpenAI rejected it or
 * preparing failed), CANCELLING → CANCELLED, EXPIRED (24 h window passed). A cancelled or expired
 * batch with an output file goes through PROCESSING too, so finished chunks are not wasted.
 */
enum class BatchStatus(val label: String) {
    PREPARING("Preparing"),
    VALIDATING("Queued"),
    IN_PROGRESS("In progress"),
    FINALIZING("Finishing"),
    CANCELLING("Cancelling"),
    PROCESSING("Storing results"),
    DONE("Done"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired");

    val isFinished: Boolean get() = this in FINISHED

    /** Still worth polling (OpenAI still working, or results not stored yet). */
    val isActive: Boolean get() = this in ACTIVE

    companion object {
        val ACTIVE = setOf(VALIDATING, IN_PROGRESS, FINALIZING, CANCELLING, PROCESSING)
        val FINISHED = setOf(DONE, FAILED, CANCELLED, EXPIRED)
        val ACTIVE_NAMES: List<String> get() = ACTIVE.map { it.name }
        val FINISHED_NAMES: List<String> get() = FINISHED.map { it.name }

        /** Everything that is not finished (includes PREPARING). */
        val UNFINISHED_NAMES: List<String> get() = (entries - FINISHED).map { it.name }

        fun fromName(name: String?): BatchStatus = entries.firstOrNull { it.name == name } ?: FAILED

        /**
         * Maps an OpenAI batch status to the local one. Terminal states with an output file map to
         * PROCESSING (the results still have to be stored); [afterProcessing] gives the final state.
         */
        fun fromOpenAi(status: String?, hasOutputFile: Boolean): BatchStatus = when (status) {
            "validating" -> VALIDATING
            "in_progress" -> IN_PROGRESS
            "finalizing" -> FINALIZING
            "cancelling" -> CANCELLING
            "completed" -> if (hasOutputFile) PROCESSING else FAILED
            "expired" -> if (hasOutputFile) PROCESSING else EXPIRED
            "cancelled" -> if (hasOutputFile) PROCESSING else CANCELLED
            "failed" -> FAILED
            else -> IN_PROGRESS // unknown future state: keep polling
        }

        /** Final local state once the results of a batch that ended as [openAiStatus] are stored. */
        fun afterProcessing(openAiStatus: String?): BatchStatus = when (openAiStatus) {
            "expired" -> EXPIRED
            "cancelled" -> CANCELLED
            else -> DONE
        }
    }
}

/** Outcome of one chapter of a batch (`batch_chapters.status`). */
enum class BatchChapterStatus {
    /** Waiting for results. */
    PENDING,
    /** Translation stored (possibly with some passages re-translated live). */
    STORED,
    /** Some passage could not be produced; left for the normal live path (range job, reader, read-ahead). */
    NEEDS_LIVE,
    /** Already translated by another path when the results arrived; batch output discarded. */
    SKIPPED;

    companion object {
        fun fromName(name: String?): BatchChapterStatus = entries.firstOrNull { it.name == name } ?: PENDING
    }
}

/**
 * `custom_id` of a batch request: `"<novelId>|<chapter>|<chunkIndex>"`. The novel id may itself
 * contain '|', so parsing splits from the right.
 */
data class BatchCustomId(val novelId: String, val chapter: Int, val chunkIndex: Int) {
    override fun toString(): String = "$novelId|$chapter|$chunkIndex"

    companion object {
        fun parse(id: String?): BatchCustomId? {
            if (id.isNullOrEmpty()) return null
            val last = id.lastIndexOf('|')
            if (last <= 0) return null
            val mid = id.lastIndexOf('|', last - 1)
            if (mid <= 0) return null
            val chunk = id.substring(last + 1).toIntOrNull() ?: return null
            val chapter = id.substring(mid + 1, last).toIntOrNull() ?: return null
            if (chunk < 0 || chapter < 0) return null
            return BatchCustomId(id.substring(0, mid), chapter, chunk)
        }
    }
}

/** One line of a batch output or error file. */
data class BatchLineResult(
    val customId: String,
    /** HTTP status of the request (null when the line only has an error). */
    val statusCode: Int?,
    val content: String?,
    val finishReason: String?,
    val usage: TokenUsage?,
    val error: String?,
) {
    val succeeded: Boolean get() = statusCode == 200 && error == null && content != null
}

/** What came back for one chunk. */
sealed class ChunkOutcome {
    data class Ok(val content: String, val finishReason: String?) : ChunkOutcome()

    /** No line for this chunk in the output or error file. */
    object Missing : ChunkOutcome()

    data class Failed(val reason: String) : ChunkOutcome()
}

/** One chapter's results in chunk order, with the usage billed for it. */
data class AssembledChapter(
    val chapterNum: Int,
    val outcomes: List<ChunkOutcome>,
    val usage: TokenUsage,
) {
    val okCount: Int get() = outcomes.count { it is ChunkOutcome.Ok }
    val complete: Boolean get() = okCount == outcomes.size
}
