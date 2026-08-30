package wiki.nplus.airadar.digester

import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.BudgetExhausted
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.Freshness
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.Pause
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.Settings
import wiki.nplus.airadar.common.StageMessage
import java.net.http.HttpClient

private val log = LoggerFactory.getLogger("digester")

fun main() = wiki.nplus.airadar.common.App.main("digester") {
    val registry = wiki.nplus.airadar.common.Metrics.start("digester", 9103)
    val repo = ItemRepository(Db.dataSource("digester"))
    val http = HttpClient.newHttpClient()
    val llm = LlmClient.digesterFromEnv(http)
    val dailyBudgetUsd = Settings.dailyBudgetUsd
    val dailyDigestLimit = Settings.dailyDigestLimit
    val maxAgeDays = Settings.matchMaxAgeDays
    val connection = Rabbit.connect("digester")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    val usage = UsageMeter(repo, registry)

    val curator = CuratorJob(repo, LlmClient.selectorFromEnv(http), registry, usage)
    val essayist = EssayistJob(
        repo,
        LlmClient.essayistFromEnv(http),
        LlmClient.judgeFromEnv(http),
        wiki.nplus.airadar.common.LibraryClient.fromEnv(http),
        connection.createChannel(),
        registry,
        usage,
    )
    val curatorTickMinutes = Config.int("CURATOR_TICK_MINUTES", 5)
    if (!Pause.paused) {
        kotlin.concurrent.thread(isDaemon = true, name = "curator") {
            while (true) {
                runCatching { curator.runIfDue(java.time.Instant.now()) }
                    .onFailure { log.warn("selection attempt failed, next tick retries: {}", it.toString()) }
                runCatching { essayist.runIfDue(java.time.Instant.now()) }
                    .onFailure { log.warn("essay attempt failed, next tick retries: {}", it.toString()) }
                Thread.sleep(curatorTickMinutes * 60_000L)
            }
        }
    }

    log.info("digester: consuming {} (provider={}, model={}, budget USD {}/day)", RabbitTopology.DIGEST_QUEUE, llm.javaClass.simpleName, llm.model, dailyBudgetUsd)
    Rabbit.consume(channel, RabbitTopology.DIGEST_QUEUE, registry) { body ->
        val itemId = StageMessage.decode(body).itemId
        val item = repo.findItem(itemId) ?: error("item $itemId not found")
        if (item.state != ItemState.MATCHED.name) {
            log.info("item {} in state {}, not MATCHED — no-op", itemId, item.state)
            return@consume
        }

        val now = java.time.Instant.now()
        if (Freshness.isStale(item, now, maxAgeDays)) {
            if (repo.transition(itemId, ItemState.MATCHED, ItemState.STALE)) {
                log.info("item {} STALE ({} days old): {}", itemId, Freshness.ageDays(item, now), item.title)
            }
            return@consume
        }

        if (dailyDigestLimit > 0 && repo.digestCountToday() >= dailyDigestLimit) {
            throw BudgetExhausted("daily digest limit $dailyDigestLimit reached")
        }
        val spent = repo.costSpentToday()
        if (spent >= dailyBudgetUsd) {
            throw BudgetExhausted("spent $%.4f of $%.2f today".format(spent, dailyBudgetUsd))
        }

        val digest = usage.call(itemId, "DIGEST", llm) { llm.digest(item.source, item.title, item.url, item.extractedText) }
        repo.saveDigest(itemId, digest)
        if (repo.transition(itemId, ItemState.MATCHED, ItemState.DIGESTED)) {
            Rabbit.publish(channel, "", RabbitTopology.PUBLISH_QUEUE, StageMessage(itemId).encode())
            log.info("digested item {} (score {}): {}", itemId, digest.significanceScore, item.title)
        }
    }
}
