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
import wiki.nplus.airadar.common.StageMessage
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The daily essay (news-echo Phase 2): once per UTC day after ESSAY_HOUR_UTC,
 * walk the shortlist pool strongest-resonance-first, and for each pick ask the
 * cheap-tier relevance judge whether the book evidence genuinely illuminates
 * the news — at most ESSAY_JUDGE_MAX_CANDIDATES verdicts per day. The first
 * survivor gets its top chapters pulled in full and ESSAY_MODEL writes the
 * book-informed commentary (one essay attempt per day); the essay model may
 * still refuse (skip). Every draft's quotes are then checked against the source
 * text ([QuoteVerifier]) before it can publish. Rejected and skipped picks are
 * consumed so the same dead pairing is not retried tomorrow. Days without an
 * essay are a legal outcome (寧缺勿濫).
 *
 * Runs in the digester process for the same reason as the curator (ADR-009):
 * one process spends all LLM money.
 */
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
    private val ttlDays = Config.int("SHORTLIST_TTL_DAYS", 7)
    // 2 章、每章 6000 字是為 2.5-pro 的 context 訂的。essay 層現在跑 3.x（大得
    // 多的 context，而且一天只呼叫一次），書證量對深度的影響比換模型本身更大。
    // ESSAY_CHAPTER_CHARS 同時被 prompt 與 publisher 的引文標記讀取，三邊必須是
    // 同一個數字。
    private val maxChapters = Config.int("ESSAY_MAX_CHAPTERS", 3)
    private val chapterChars = Config.int("ESSAY_CHAPTER_CHARS", 12000)
    /** 引文對不上時，允許帶著「哪幾句對不上」重寫一次（ADR-012）。 */
    private val reviseOnUnverifiedQuotes = Config.bool("ESSAY_REVISE_ON_BAD_QUOTES", true)
    private val maxJudged = Config.int("ESSAY_JUDGE_MAX_CANDIDATES", 3)
    private val dailyBudgetUsd = Config.double("DAILY_LLM_BUDGET_USD", 0.50)
    private val attempts = DailyAttemptGuard(Config.int("DAILY_JOB_MAX_ATTEMPTS", 3))
    /**
     * 逾時退還嘗試（2026-08-03），但退還本身也要記帳：一次逾時代表模型很可能
     * 已經生成完畢、只是我們沒等到，錢照付而帳本記不到（沒有回應就沒有
     * usageMetadata）。所以無上限的退還等於繞過 DAILY_JOB_MAX_ATTEMPTS。
     * 最壞情況是 3 次嘗試 + 2 次退還 = 5 次呼叫，DAILY_LLM_BUDGET_USD 仍是硬底線。
     */
    private val timeoutRefunds = DailyAttemptGuard(Config.int("DAILY_JOB_MAX_TIMEOUT_REFUNDS", 2))
    /** 已經為哪一天喊過 stand-down —— 每個 tick 重喊會讓一次失敗被計成十幾次。 */
    private var standDownDay: LocalDate? = null
    private fun outcome(name: String) = registry.counter("airadar_essay_runs_total", "outcome", name)

    /**
     * 「最後一次真的寫出 essay 是什麼時候」。
     *
     * outcome counter 說得出每一輪的結果，卻說不出「已經幾天沒有文章了」——而
     * 停更正是這條 pipeline 壞掉的樣子：佇列清空、站台照建、所有指標全綠，只是
     * 沒有東西發出去（2026-07-18/19 的 critic loop 就是這樣過了兩天）。
     *
     * 起始值從 DB 回填，容器重啟不會把時鐘歸零；真的一篇都沒有時退回啟動時間，
     * 而不是 0 —— time() - 0 是 56 年，會在第一次抓取就誤報。
     */
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
            // No pending pick: not an error, the pool refills as news resonates.
            return
        }

        val spent = repo.costSpentToday()
        if (spent >= dailyBudgetUsd) {
            outcome("budget_skipped").increment()
            log.warn("essay {}: skipped, ${"$%.4f".format(spent)} of ${"$%.2f".format(dailyBudgetUsd)} spent", day)
            return
        }

        // Everything below spends, and nothing below is recorded as "the day is
        // done" until the very end — so a failure in between comes straight back
        // on the next tick and pays again. Cap how often that can happen.
        if (!attempts.tryConsume(day)) {
            // 只在進入 stand-down 的那一刻計一次。這個分支每 5 分鐘會再走一遍，
            // 原本每遍都 increment，一次失敗在告警上被算成 19 次（TODO #23）。
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
            // 沒等到答案不算用掉一次嘗試 —— 但退還額度自己有上限（見
            // timeoutRefunds），否則一個持續逾時的上游可以無限重試、無限計費。
            if (e.timedOut && timeoutRefunds.tryConsume(day)) {
                attempts.refund(day)
                outcome("timeout_refunded").increment()
                log.warn("essay {}: 逾時，退還這次嘗試（下一個 tick 重試）: {}", day, e.message)
            }
            throw e
        }
    }

    private fun compose(day: LocalDate, candidates: List<ItemRepository.EssayCandidate>) {
        // The relevance judge (cheap tier) runs BEFORE the essay: vector
        // distance measures library density, not relatedness (2026-07-16 live
        // calibration), so a keyword coincidence must be caught here — an
        // essay built on a fake pairing would be published. Judged-unrelated
        // picks are consumed so the same dead pairing is not retried tomorrow.
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
            // Consume the pick: retrying the same pairing tomorrow would burn
            // the same money on the same dead end.
            repo.markComposed(candidate.itemId)
            outcome("skipped").increment()
            log.info("essay {}: model declined item {} ({}): {}", day, candidate.itemId, candidate.title, result.skipReason)
            return
        }

        // Quote gate: the essay prompt requires book quotes to be blockquotes
        // precisely so this check can be a string comparison instead of another
        // model. A fabricated quote is the one flaw the author model cannot
        // catch in itself and the one a reader would never forgive; everything
        // else about draft quality the prompt and the skip clause already own.
        var essayMd = result.essayMd ?: error("essay without body for item ${candidate.itemId}")
        val sources = quoteSources(candidate, chapters)
        var quotes = QuoteVerifier.verify(essayMd, sources)

        // 一次修訂（ADR-012，修訂 ADR-011 的「寧可放棄也不重寫」）。
        //
        // ADR-011 拒絕重寫，理由是重寫等於在最貴的一層再付一次錢，而觸發它的是
        // 模型對模型的評分——那種迴圈自己不會停。這裡兩個前提都不同：觸發者是
        // 確定性的字串比對（它只會在真的有假引文時響），而且只准一次。放棄一天
        // 的代價是整整一天沒有專欄，而失敗的往往只是三段引文裡的一段。
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
                    // 修訂稿只有在真的通過時才取代原稿：改壞了就用原稿走剩下的
                    // 流程（它一樣過不了關，但至少 log 裡是同一份稿子）。
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

        // 修訂之後仍然對不上就放棄這一天：pick 被消耗掉，明天從別的配對開始，
        // 而不是再買一次同一個。
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

    /**
     * Everything the essayist was allowed to quote from: the chapters it got in
     * full, the retrieved passages, and the news article itself (an essay quotes
     * the news it responds to as legitimately as it quotes a book).
     */
    private fun quoteSources(
        candidate: ItemRepository.EssayCandidate,
        chapters: List<LlmClient.ChapterExcerpt>,
    ): List<String> = chapters.map { it.content } +
        listOfNotNull(candidate.passagesJson, candidate.extractedText, candidate.title)

    /** Fetch full text for the strongest distinct chapters among the match passages. */
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
