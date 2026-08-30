package wiki.nplus.airadar.matcher

import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.Freshness
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.LibraryClient
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.Settings
import wiki.nplus.airadar.common.StageMessage
import java.net.http.HttpClient
import java.time.Instant

private val log = LoggerFactory.getLogger("matcher")

fun main() = wiki.nplus.airadar.common.App.main("matcher") {
    val registry = wiki.nplus.airadar.common.Metrics.start("matcher", 9105)
    val repo = ItemRepository(Db.dataSource("matcher"))
    val library = LibraryClient.fromEnv(HttpClient.newHttpClient())
    val noResonanceDistance = Settings.matchNoResonanceDistance
    val queryChars = Config.int("MATCH_QUERY_CHARS", 1500)
    val maxAgeDays = Settings.matchMaxAgeDays
    val connection = Rabbit.connect("matcher")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    fun outcome(name: String) = registry.counter("airadar_match_total", "outcome", name)

    log.info(
        "matcher: consuming {} (provider={}, trash filter beyond {}, freshness cutoff {}d)",
        RabbitTopology.MATCH_QUEUE, library.javaClass.simpleName, noResonanceDistance, maxAgeDays,
    )
    Rabbit.consume(channel, RabbitTopology.MATCH_QUEUE, registry) { body ->
        val itemId = StageMessage.decode(body).itemId
        val item = repo.findItem(itemId) ?: error("item $itemId not found")
        if (item.state != ItemState.ENRICHED.name) {
            log.info("item {} already in state {}, redelivery no-op", itemId, item.state)
            return@consume
        }

        val now = Instant.now()
        if (Freshness.isStale(item, now, maxAgeDays)) {
            if (repo.transition(itemId, ItemState.ENRICHED, ItemState.STALE)) {
                outcome("stale").increment()
                log.info("item {} STALE ({} days old, before search): {}", itemId, Freshness.ageDays(item, now), item.title)
            }
            return@consume
        }

        val query = buildString {
            append(item.title)
            item.extractedText?.let { append('\n').append(it.take(queryChars)) }
        }
        val result = library.search(query)
        val distance = result.topBookDistance
            ?: error("library /search returned no books for item $itemId — index empty or corpus mount broken?")
        repo.saveMatch(itemId, distance, result.booksJson, result.passagesJson)

        if (distance > noResonanceDistance) {
            if (repo.transition(itemId, ItemState.ENRICHED, ItemState.NO_RESONANCE)) {
                outcome("no_resonance").increment()
                log.info("item {} NO_RESONANCE (top book {}): {}", itemId, distance, item.title)
            }
        } else {
            if (repo.transition(itemId, ItemState.ENRICHED, ItemState.MATCHED)) {
                Rabbit.publish(channel, "", RabbitTopology.DIGEST_QUEUE, StageMessage(itemId).encode())
                outcome("matched").increment()
                log.info("item {} MATCHED (top book {}): {}", itemId, distance, item.title)
            }
        }
    }
}
