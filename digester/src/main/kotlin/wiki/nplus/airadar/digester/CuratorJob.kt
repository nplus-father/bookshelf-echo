package wiki.nplus.airadar.digester

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.SelectResult
import wiki.nplus.airadar.common.Settings
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The daily selection (ADR-009): once per UTC day, after SELECT_HOUR_UTC, rank
 * everything digested since the previous run RELATIVE to each other and put at
 * most SHORTLIST_MAX_PER_DAY picks into the shortlist pool — the items worth a
 * deep, book-informed commentary (M6 consumes the pool).
 *
 * This runs inside the digester process on purpose: the daily budget check has
 * no DB-level guard (see Main), so every LLM spender must live in the one
 * process that serializes those checks.
 *
 * Crash window: usage is recorded before the run row, so a crash in between
 * re-runs the selection on the next tick — shortlist inserts are ON CONFLICT
 * DO NOTHING, so the worst case is one duplicate LLM call, never duplicate picks.
 */
class CuratorJob(
    private val repo: ItemRepository,
    private val selector: LlmClient,
    private val registry: MeterRegistry,
    private val usage: UsageMeter,
) {
    private val log = LoggerFactory.getLogger(CuratorJob::class.java)
    private val selectHourUtc = Config.int("SELECT_HOUR_UTC", 21)

    // Why it is 2 and not 3: Settings.shortlistMaxPerDay.
    private val maxPicks = Settings.shortlistMaxPerDay
    private val minScore = Config.int("SELECT_MIN_SCORE", 3)
    private val dailyBudgetUsd = Settings.dailyBudgetUsd
    private val attempts = DailyAttemptGuard(Settings.dailyJobMaxAttempts)
    private fun outcome(name: String) = registry.counter("airadar_selection_runs_total", "outcome", name)

    fun runIfDue(now: Instant) {
        val utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        if (utcNow.hour < selectHourUtc) return
        val day = utcNow.toLocalDate()
        if (repo.selectionRunExists(day)) return

        // Window since the last run (not "today"): a day the curator missed —
        // budget spent, process down — folds into the next run instead of its
        // digests silently never becoming candidates.
        val since = repo.lastSelectionRunAt() ?: utcNow.minus(48, ChronoUnit.HOURS)
        val candidates = repo.selectionCandidates(since, minScore)
        if (candidates.isEmpty()) {
            repo.recordSelectionRun(day, selector.model, 0, 0)
            outcome("empty").increment()
            log.info("selection {}: no candidates since {}", day, since)
            return
        }

        // Same circuit breaker as the digest path; not recording the run means
        // the next tick (or day) retries once the budget window resets.
        val spent = repo.costSpentToday()
        if (spent >= dailyBudgetUsd) {
            outcome("budget_skipped").increment()
            log.warn("selection {}: skipped, ${"$%.4f".format(spent)} of ${"$%.2f".format(dailyBudgetUsd)} spent", day)
            return
        }

        // The crash window above is bounded by attempts, not by luck: the tick
        // loop would otherwise re-run a deterministic failure — and re-buy the
        // pro-tier selection — every CURATOR_TICK_MINUTES until midnight.
        if (!attempts.tryConsume(day)) {
            outcome("attempts_exhausted").increment()
            log.error("selection {}: attempts exhausted, standing down until tomorrow (or a restart)", day)
            return
        }

        val result = usage.call(null, "SELECT", selector) { selector.select(candidates, maxPicks) }
        val picks = validatePicks(result, candidates, maxPicks)
        picks.forEach { repo.saveShortlistPick(it.itemId, it.reason, result.model) }
        repo.recordSelectionRun(day, result.model, candidates.size, picks.size)
        outcome("picked").increment()
        log.info(
            "selection {}: picked {}/{} candidates ({}): {}",
            day, picks.size, candidates.size, result.model, picks.joinToString { "#${it.itemId}" },
        )
    }

    companion object {
        /** Drop hallucinated ids and clamp to the cap — the model's list is a suggestion, not a command. */
        fun validatePicks(
            result: SelectResult,
            candidates: List<ItemRepository.SelectionCandidate>,
            maxPicks: Int,
        ): List<SelectResult.Pick> {
            val known = candidates.mapTo(HashSet()) { it.item.itemId }
            return result.picks.filter { it.itemId in known }.distinctBy { it.itemId }.take(maxPicks)
        }
    }
}
