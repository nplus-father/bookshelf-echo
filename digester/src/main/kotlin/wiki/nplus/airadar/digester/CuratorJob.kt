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

class CuratorJob(
    private val repo: ItemRepository,
    private val selector: LlmClient,
    private val registry: MeterRegistry,
    private val usage: UsageMeter,
) {
    private val log = LoggerFactory.getLogger(CuratorJob::class.java)
    private val selectHourUtc = Config.int("SELECT_HOUR_UTC", 21)

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

        val since = repo.lastSelectionRunAt() ?: utcNow.minus(48, ChronoUnit.HOURS)
        val candidates = repo.selectionCandidates(since, minScore)
        if (candidates.isEmpty()) {
            repo.recordSelectionRun(day, selector.model, 0, 0)
            outcome("empty").increment()
            log.info("selection {}: no candidates since {}", day, since)
            return
        }

        val spent = repo.costSpentToday()
        if (spent >= dailyBudgetUsd) {
            outcome("budget_skipped").increment()
            log.warn("selection {}: skipped, ${"$%.4f".format(spent)} of ${"$%.2f".format(dailyBudgetUsd)} spent", day)
            return
        }

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
