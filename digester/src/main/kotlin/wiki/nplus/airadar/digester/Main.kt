package wiki.nplus.airadar.digester

import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.BudgetExhausted
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.StageMessage
import java.net.http.HttpClient

private val log = LoggerFactory.getLogger("digester")

fun main() = wiki.nplus.airadar.common.App.main("digester") {
    val registry = wiki.nplus.airadar.common.Metrics.start("digester", 9103)
    val repo = ItemRepository(Db.dataSource("digester"))
    val http = HttpClient.newHttpClient()
    val llm = LlmClient.fromEnv(http)
    val dailyBudgetUsd = Config.double("DAILY_LLM_BUDGET_USD", 0.50)
    val dailyDigestLimit = Config.int("DAILY_DIGEST_LIMIT", 10) // high-value items to digest per UTC day; 0 = unlimited
    val maxAgeDays = Config.long("MATCH_MAX_AGE_DAYS", 3) // news older than this is dropped STALE before spend; 0 = off
    val connection = Rabbit.connect("digester")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    val inputTokens = registry.counter("airadar_llm_tokens_total", "type", "input")
    val outputTokens = registry.counter("airadar_llm_tokens_total", "type", "output")
    val cost = registry.counter("airadar_llm_cost_usd_total")

    // Daily jobs (ADR-009) run in THIS process: the budget check below has no
    // DB-level guard, so a second LLM-spending process would race it. Both are
    // tick loops, not consumers — their unit of work is "the day's candidate
    // set", which no per-item queue message can represent.
    val curator = CuratorJob(repo, LlmClient.selectorFromEnv(http), registry)
    val essayist = EssayistJob(
        repo,
        LlmClient.essayistFromEnv(http),
        LlmClient.judgeFromEnv(http),
        wiki.nplus.airadar.common.LibraryClient.fromEnv(http),
        connection.createChannel(),
        registry,
    )
    val curatorTickMinutes = Config.int("CURATOR_TICK_MINUTES", 5)
    kotlin.concurrent.thread(isDaemon = true, name = "curator") {
        while (true) {
            runCatching { curator.runIfDue(java.time.Instant.now()) }
                .onFailure { log.warn("selection attempt failed, next tick retries: {}", it.toString()) }
            runCatching { essayist.runIfDue(java.time.Instant.now()) }
                .onFailure { log.warn("essay attempt failed, next tick retries: {}", it.toString()) }
            Thread.sleep(curatorTickMinutes * 60_000L)
        }
    }

    log.info("digester: consuming {} (provider={}, model={}, budget USD {}/day)", RabbitTopology.DIGEST_QUEUE, llm.javaClass.simpleName, llm.model, dailyBudgetUsd)
    Rabbit.consume(channel, RabbitTopology.DIGEST_QUEUE, registry) { body ->
        val itemId = StageMessage.decode(body).itemId
        val item = repo.findItem(itemId) ?: error("item $itemId not found")
        // Items now arrive through the resonance gate (ADR-010): matcher owns
        // ENRICHED → MATCHED. An ENRICHED item on this queue is pre-gate
        // backlog from before the matcher existed — `ops redrive` re-routes it.
        if (item.state != ItemState.MATCHED.name) {
            log.info("item {} in state {}, not MATCHED — no-op", itemId, item.state)
            return@consume
        }

        // Freshness cutoff (V5): the daily cap is FIFO, so a backlog of old
        // items would starve fresh ones and we'd write commentary off week-old
        // news. Drop anything past the cutoff to STALE, terminal, at zero cost —
        // checked BEFORE the cap so stale items drain out of the retry cycle
        // instead of re-parking and blocking the queue behind them. Clock is
        // the news's own date, received_at only as a fallback.
        if (maxAgeDays > 0) {
            val reference = (item.publishedAt ?: item.receivedAt).toInstant()
            val ageDays = java.time.Duration.between(reference, java.time.Instant.now()).toDays()
            if (ageDays > maxAgeDays) {
                if (repo.transition(itemId, ItemState.MATCHED, ItemState.STALE)) {
                    log.info("item {} STALE ({} days old): {}", itemId, ageDays, item.title)
                }
                return@consume
            }
        }

        // Daily selection cap: digest at most N items/day so the spend goes to a
        // small set of high-value items. Excess re-parks via the same
        // BudgetExhausted path and waits for the next UTC day's window.
        if (dailyDigestLimit > 0 && repo.digestCountToday() >= dailyDigestLimit) {
            throw BudgetExhausted("daily digest limit $dailyDigestLimit reached")
        }
        // Cost circuit breaker (design doc §3.4): once the daily budget is spent
        // the backlog waits in the queue — BudgetExhausted re-parks without
        // burning a retry attempt.
        val spent = repo.costSpentToday()
        if (spent >= dailyBudgetUsd) {
            throw BudgetExhausted("spent $%.4f of $%.2f today".format(spent, dailyBudgetUsd))
        }

        val digest = llm.digest(item.source, item.title, item.url, item.extractedText)
        repo.saveDigest(itemId, digest)
        val usd = llm.cost(digest.inputTokens, digest.outputTokens)
        repo.recordUsage(itemId, "DIGEST", digest.model, digest.inputTokens, digest.outputTokens, usd)
        inputTokens.increment(digest.inputTokens.toDouble())
        outputTokens.increment(digest.outputTokens.toDouble())
        cost.increment(usd)
        if (repo.transition(itemId, ItemState.MATCHED, ItemState.DIGESTED)) {
            Rabbit.publish(channel, "", RabbitTopology.PUBLISH_QUEUE, StageMessage(itemId).encode())
            log.info("digested item {} (score {}): {}", itemId, digest.significanceScore, item.title)
        }
    }
}
