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

fun main() {
    val registry = wiki.nplus.airadar.common.Metrics.start("digester", 9103)
    val repo = ItemRepository(Db.dataSource("digester"))
    val llm = LlmClient.fromEnv(HttpClient.newHttpClient())
    val dailyBudgetUsd = Config.double("DAILY_LLM_BUDGET_USD", 0.50)
    val connection = Rabbit.connect("digester")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    val inputTokens = registry.counter("airadar_llm_tokens_total", "type", "input")
    val outputTokens = registry.counter("airadar_llm_tokens_total", "type", "output")
    val cost = registry.counter("airadar_llm_cost_usd_total")

    log.info("digester: consuming {} (provider={}, model={}, budget USD {}/day)", RabbitTopology.DIGEST_QUEUE, llm.javaClass.simpleName, llm.model, dailyBudgetUsd)
    Rabbit.consume(channel, RabbitTopology.DIGEST_QUEUE, registry) { body ->
        val itemId = StageMessage.decode(body).itemId
        val item = repo.findItem(itemId) ?: error("item $itemId not found")
        if (item.state != ItemState.ENRICHED.name) {
            log.info("item {} already in state {}, redelivery no-op", itemId, item.state)
            return@consume
        }

        // Circuit breaker (design doc §3.4): once the daily budget is spent the
        // backlog waits in the queue — BudgetExhausted re-parks without burning
        // a retry attempt.
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
        if (repo.transition(itemId, ItemState.ENRICHED, ItemState.DIGESTED)) {
            Rabbit.publish(channel, "", RabbitTopology.PUBLISH_QUEUE, StageMessage(itemId).encode())
            log.info("digested item {} (score {}): {}", itemId, digest.significanceScore, item.title)
        }
    }
}
