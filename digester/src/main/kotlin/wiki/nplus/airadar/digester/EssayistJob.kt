package wiki.nplus.airadar.digester

import com.rabbitmq.client.Channel
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.EssayFeedback
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.LibraryClient
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.StageMessage
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The daily essay (news-echo Phase 2): once per UTC day after ESSAY_HOUR_UTC,
 * walk the shortlist pool strongest-resonance-first, and for each pick ask the
 * cheap-tier relevance judge whether the book evidence genuinely illuminates
 * the news — at most ESSAY_JUDGE_MAX_CANDIDATES verdicts per day. The first
 * survivor gets its top chapters pulled in full and ESSAY_MODEL writes the
 * book-informed commentary (one essay attempt per day); the essay model may
 * still refuse (skip). Every draft then faces the cheap-tier critic; a fail
 * earns one revision with the critique fed back, a second fail forfeits the
 * day. Rejected and skipped picks are consumed so the same dead pairing is
 * not retried tomorrow. Days without an essay are a legal outcome (寧缺勿濫).
 *
 * Runs in the digester process for the same reason as the curator (ADR-009):
 * one process spends all LLM money.
 */
class EssayistJob(
    private val repo: ItemRepository,
    private val essayist: LlmClient,
    private val judge: LlmClient,
    private val critic: LlmClient,
    private val library: LibraryClient,
    private val channel: Channel,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(EssayistJob::class.java)
    private val essayHourUtc = Config.int("ESSAY_HOUR_UTC", 22)
    private val ttlDays = Config.int("SHORTLIST_TTL_DAYS", 7)
    private val maxChapters = Config.int("ESSAY_MAX_CHAPTERS", 2)
    private val maxJudged = Config.int("ESSAY_JUDGE_MAX_CANDIDATES", 3)
    private val dailyBudgetUsd = Config.double("DAILY_LLM_BUDGET_USD", 0.50)
    private fun outcome(name: String) = registry.counter("airadar_essay_runs_total", "outcome", name)

    fun runIfDue(now: Instant) {
        val utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        if (utcNow.hour < essayHourUtc) return
        val day = utcNow.toLocalDate()
        if (repo.essayExistsForDay(day)) return

        val candidates = repo.essayCandidates(ttlDays)
        if (candidates.isEmpty()) {
            // No pending pick: not an error, the pool refills as news resonates.
            return
        }

        val spent = repo.costSpentToday()
        if (spent >= dailyBudgetUsd) {
            outcome("budget_skipped").increment()
            log.warn("essay {}: skipped, ${"$%.4f".format(spent)} of ${"$%.2f".format(dailyBudgetUsd)} spent", day)
            return
        }

        // The relevance judge (cheap tier) runs BEFORE the essay: vector
        // distance measures library density, not relatedness (2026-07-16 live
        // calibration), so a keyword coincidence must be caught here — an
        // essay built on a fake pairing would be published. Judged-unrelated
        // picks are consumed so the same dead pairing is not retried tomorrow.
        val candidate = candidates.take(maxJudged).firstOrNull { c ->
            val verdict = judge.judge(c)
            repo.recordUsage(c.itemId, "JUDGE", verdict.model, verdict.inputTokens, verdict.outputTokens, judge.cost(verdict.inputTokens, verdict.outputTokens))
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
        var result = essayist.essay(candidate, chapters)
        repo.recordUsage(candidate.itemId, "ESSAY", result.model, result.inputTokens, result.outputTokens, essayist.cost(result.inputTokens, result.outputTokens))

        if (result.skip) {
            // Consume the pick: retrying the same pairing tomorrow would burn
            // the same money on the same dead end.
            repo.markComposed(candidate.itemId)
            outcome("skipped").increment()
            log.info("essay {}: model declined item {} ({}): {}", day, candidate.itemId, candidate.title, result.skipReason)
            return
        }

        // Critic gate: every draft is reviewed before publishing; a fail earns
        // exactly one revision round (the critique goes back verbatim), a
        // second fail forfeits the day. The failure mode this catches is a
        // draft that is fluent but hollow — summary sandwich, fake quotes,
        // forced pairing — which the essayist's own honesty clause misses
        // because it judges the pairing, not its own prose.
        val verdict = critic.critique(candidate, chapters, result.essayMd ?: error("essay without body for item ${candidate.itemId}"))
        repo.recordUsage(candidate.itemId, "CRITIC", verdict.model, verdict.inputTokens, verdict.outputTokens, critic.cost(verdict.inputTokens, verdict.outputTokens))
        if (!verdict.pass) {
            log.info("essay {}: critic rejected draft for item {} ({}): {}", day, candidate.itemId, candidate.title, verdict.critique)
            val revised = essayist.essay(candidate, chapters, EssayFeedback(result.essayMd!!, verdict.critique))
            repo.recordUsage(candidate.itemId, "ESSAY_REVISE", revised.model, revised.inputTokens, revised.outputTokens, essayist.cost(revised.inputTokens, revised.outputTokens))
            if (revised.skip) {
                repo.markComposed(candidate.itemId)
                outcome("skipped").increment()
                log.info("essay {}: model declined on revision for item {} ({}): {}", day, candidate.itemId, candidate.title, revised.skipReason)
                return
            }
            val second = critic.critique(candidate, chapters, revised.essayMd ?: error("revised essay without body for item ${candidate.itemId}"))
            repo.recordUsage(candidate.itemId, "CRITIC", second.model, second.inputTokens, second.outputTokens, critic.cost(second.inputTokens, second.outputTokens))
            if (!second.pass) {
                repo.markComposed(candidate.itemId)
                outcome("critic_rejected").increment()
                log.info("essay {}: revision still rejected for item {} ({}) — no essay today (寧缺勿濫): {}", day, candidate.itemId, candidate.title, second.critique)
                return
            }
            result = revised
        }

        repo.saveEssay(
            day = day,
            itemId = candidate.itemId,
            title = result.titleZh ?: candidate.title,
            essayMd = result.essayMd ?: error("essay without body for item ${candidate.itemId}"),
            booksJson = result.booksJson,
            model = result.model,
        )
        repo.markComposed(candidate.itemId)
        Rabbit.publish(channel, "", RabbitTopology.PUBLISH_QUEUE, StageMessage(candidate.itemId, kind = "essay").encode())
        outcome("composed").increment()
        log.info("essay {}: composed from item {} ({}), {} book(s)", day, candidate.itemId, candidate.title, Json.parseToJsonElement(result.booksJson).jsonArray.size)
    }

    /** Fetch full text for the strongest distinct chapters among the match passages. */
    private fun topChapters(passagesJson: String): List<LlmClient.ChapterExcerpt> =
        Json.parseToJsonElement(passagesJson).jsonArray
            .map { it.jsonObject }
            .distinctBy { it["chapter_id"]?.jsonPrimitive?.content }
            .take(maxChapters)
            .mapNotNull { p ->
                val chapterId = p["chapter_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val content = library.chapter(chapterId) ?: return@mapNotNull null
                LlmClient.ChapterExcerpt(
                    bookTitle = p["book_title"]?.jsonPrimitive?.content ?: "",
                    chapterTitle = p["chapter_title"]?.jsonPrimitive?.content ?: "",
                    chapterId = chapterId,
                    content = content,
                )
            }
}
