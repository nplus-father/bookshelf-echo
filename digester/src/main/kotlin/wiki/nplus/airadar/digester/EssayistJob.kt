package wiki.nplus.airadar.digester

import com.rabbitmq.client.Channel
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.LibraryClient
import wiki.nplus.airadar.common.QuoteVerifier
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.RetryableFailure
import wiki.nplus.airadar.common.Settings
import wiki.nplus.airadar.common.StageMessage
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class EssayistJob(
    private val repo: ItemRepository,
    private val essayist: LlmClient,
    private val judge: LlmClient,
    private val library: LibraryClient,
    private val channel: Channel,
    private val registry: MeterRegistry,
    private val usage: UsageMeter,
) {
    private val log = LoggerFactory.getLogger(EssayistJob::class.java)
    private val essayHourUtc = Config.int("ESSAY_HOUR_UTC", 22)
    private val ttlDays = Settings.shortlistTtlDays

    private val maxChapters = Config.int("ESSAY_MAX_CHAPTERS", 3)
    private val chapterChars = Settings.essayChapterChars

    private val reviseOnUnverifiedQuotes = Config.bool("ESSAY_REVISE_ON_BAD_QUOTES", true)
    private val maxJudged = Config.int("ESSAY_JUDGE_MAX_CANDIDATES", 3)
    private val dailyBudgetUsd = Settings.dailyBudgetUsd
    private val attempts = DailyAttemptGuard(Settings.dailyJobMaxAttempts)

    private val timeoutRefunds = DailyAttemptGuard(Config.int("DAILY_JOB_MAX_TIMEOUT_REFUNDS", 2))

    private var standDownDay: LocalDate? = null
    private fun outcome(name: String) = registry.counter("airadar_essay_runs_total", "outcome", name)

    private val lastEssayEpoch = java.util.concurrent.atomic.AtomicLong(
        repo.lastEssayAt()?.toEpochSecond() ?: (System.currentTimeMillis() / 1000),
    )

    init {
        io.micrometer.core.instrument.Gauge
            .builder("airadar_essay_last_success_timestamp_seconds", lastEssayEpoch) { it.get().toDouble() }
            .description("最後一次成功產出每日評析的 UNIX 時間；缺席一天合法，連續數天不是")
            .register(registry)
    }

    fun runIfDue(now: Instant) {
        val utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        if (utcNow.hour < essayHourUtc) return
        val day = utcNow.toLocalDate()
        if (repo.essayExistsForDay(day)) return

        val candidates = repo.essayCandidates(ttlDays)
        if (candidates.isEmpty()) {
            return
        }

        val spent = repo.costSpentToday()
        if (spent >= dailyBudgetUsd) {
            outcome("budget_skipped").increment()
            log.warn("essay {}: skipped, ${"$%.4f".format(spent)} of ${"$%.2f".format(dailyBudgetUsd)} spent", day)
            return
        }

        if (!attempts.tryConsume(day)) {
            if (standDownDay != day) {
                standDownDay = day
                outcome("attempts_exhausted").increment()
                log.error("essay {}: attempts exhausted, standing down until tomorrow (or a restart)", day)
            }
            return
        }

        try {
            compose(day, candidates)
        } catch (e: RetryableFailure) {
            if (e.timedOut && timeoutRefunds.tryConsume(day)) {
                attempts.refund(day)
                outcome("timeout_refunded").increment()
                log.warn("essay {}: 逾時，退還這次嘗試（下一個 tick 重試）: {}", day, e.message)
            }
            throw e
        }
    }

    private fun compose(day: LocalDate, candidates: List<ItemRepository.EssayCandidate>) {
        val candidate = candidates.take(maxJudged).firstOrNull { c ->
            val verdict = usage.call(c.itemId, "JUDGE", judge) { judge.judge(c) }
            if (!verdict.related) {
                repo.markComposed(c.itemId)
                outcome("judge_rejected").increment()
                log.info("essay {}: judge rejected item {} ({}): {}", day, c.itemId, c.title, verdict.reason)
            }
            verdict.related
        }
        if (candidate == null) {
            log.info("essay {}: no candidate survived the judge — no essay today (寧缺勿濫)", day)
            return
        }

        val chapters = topChapters(candidate.passagesJson)
        var result = usage.call(candidate.itemId, "ESSAY", essayist) { essayist.essay(candidate, chapters) }

        if (result.skip) {
            repo.markComposed(candidate.itemId)
            outcome("skipped").increment()
            log.info("essay {}: model declined item {} ({}): {}", day, candidate.itemId, candidate.title, result.skipReason)
            return
        }

        var essayMd = result.essayMd ?: error("essay without body for item ${candidate.itemId}")
        val sources = quoteSources(candidate, chapters)
        var quotes = QuoteVerifier.verify(essayMd, sources)

        if (!quotes.ok && reviseOnUnverifiedQuotes) {
            val spentBeforeRevision = repo.costSpentToday()
            if (spentBeforeRevision >= dailyBudgetUsd) {
                outcome("revision_budget_skipped").increment()
                log.warn("essay {}: {} bad quote(s) but ${"$%.4f".format(spentBeforeRevision)} already spent — no revision", day, quotes.unverified.size)
            } else {
                outcome("revised").increment()
                log.info("essay {}: {} quote(s) unverified, asking for one revision", day, quotes.unverified.size)
                val revised = usage.call(candidate.itemId, "ESSAY", essayist) {
                    essayist.essay(candidate, chapters, quotes.unverified)
                }
                val revisedMd = revised.essayMd
                if (!revised.skip && revisedMd != null) {
                    val revisedQuotes = QuoteVerifier.verify(revisedMd, sources)
                    if (revisedQuotes.ok) {
                        result = revised
                        essayMd = revisedMd
                        quotes = revisedQuotes
                    } else {
                        log.warn("essay {}: revision still has {} bad quote(s)", day, revisedQuotes.unverified.size)
                    }
                } else {
                    log.warn("essay {}: revision declined ({})", day, revised.skipReason)
                }
            }
        }

        if (!quotes.ok) {
            repo.markComposed(candidate.itemId)
            outcome("unverified_quotes").increment()
            log.warn(
                "essay {}: {} quote(s) not found in the source material for item {} ({}) — no essay today (寧缺勿濫): {}",
                day, quotes.unverified.size, candidate.itemId, candidate.title,
                quotes.unverified.joinToString(" | ") { it.take(40) },
            )
            return
        }

        repo.saveEssay(
            day = day,
            itemId = candidate.itemId,
            title = result.titleZh ?: candidate.title,
            essayMd = essayMd,
            booksJson = result.booksJson,
            model = result.model,
        )
        repo.markComposed(candidate.itemId)
        Rabbit.publish(channel, "", RabbitTopology.PUBLISH_QUEUE, StageMessage(candidate.itemId, kind = "essay").encode())
        outcome("composed").increment()
        lastEssayEpoch.set(System.currentTimeMillis() / 1000)
        log.info("essay {}: composed from item {} ({}), {} book(s)", day, candidate.itemId, candidate.title, Json.parseToJsonElement(result.booksJson).jsonArray.size)
    }

    private fun quoteSources(
        candidate: ItemRepository.EssayCandidate,
        chapters: List<LlmClient.ChapterExcerpt>,
    ): List<String> = chapters.map { it.content } +
        listOfNotNull(candidate.passagesJson, candidate.extractedText, candidate.title)

    private fun topChapters(passagesJson: String): List<LlmClient.ChapterExcerpt> =
        Json.parseToJsonElement(passagesJson).jsonArray
            .map { it.jsonObject }
            .distinctBy { it["chapter_id"]?.jsonPrimitive?.content }
            .take(maxChapters)
            .mapNotNull { p ->
                val chapterId = p["chapter_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val content = library.chapter(chapterId, chapterChars) ?: return@mapNotNull null
                LlmClient.ChapterExcerpt(
                    bookTitle = p["book_title"]?.jsonPrimitive?.content ?: "",
                    chapterTitle = p["chapter_title"]?.jsonPrimitive?.content ?: "",
                    chapterId = chapterId,
                    content = content,
                )
            }
}
