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
import wiki.nplus.airadar.common.StageMessage
import java.net.http.HttpClient
import java.time.Instant

private val log = LoggerFactory.getLogger("matcher")

/**
 * The evidence stage (ADR-010 as amended).
 *
 * It was built as a gate: reject anything whose nearest book sits beyond
 * MATCH_NO_RESONANCE_DISTANCE before a cent of LLM money is spent. The
 * amendment, and then the live ledger, retired that ambition — absolute cosine
 * distance measures how dense the library is around a topic, not whether the
 * bookshelf has anything to say about it. Across 661 live matches the 1.10
 * cutoff rejected 8 items (1.2%), and a 30-item hand-labelled sample showed
 * distance separating genuine resonance from coincidence not at all. The real
 * gate is the LLM relevance judge inside EssayistJob.
 *
 * What this stage is actually for is the *evidence*: one Voyage query embedding
 * buys the books and passages that the curator ranks with, the judge rules on,
 * and the essayist quotes from. The distance cutoff stays as a coarse trash
 * filter and nothing more — do not tune it against live traffic, and do not
 * read the number it produces as a relevance score (ADR-010, decision #1).
 */
fun main() = wiki.nplus.airadar.common.App.main("matcher") {
    val registry = wiki.nplus.airadar.common.Metrics.start("matcher", 9105)
    val repo = ItemRepository(Db.dataSource("matcher"))
    val library = LibraryClient.fromEnv(HttpClient.newHttpClient())
    val noResonanceDistance = Config.double("MATCH_NO_RESONANCE_DISTANCE", 1.10)
    val queryChars = Config.int("MATCH_QUERY_CHARS", 1500)
    val maxAgeDays = Config.long("MATCH_MAX_AGE_DAYS", 3) // shared with the digester; 0 = off
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

        // Freshness first: an item the digester would drop as STALE anyway must
        // not buy a query embedding and a matches row on its way there.
        val now = Instant.now()
        if (Freshness.isStale(item, now, maxAgeDays)) {
            if (repo.transition(itemId, ItemState.ENRICHED, ItemState.STALE)) {
                outcome("stale").increment()
                log.info("item {} STALE ({} days old, before search): {}", itemId, Freshness.ageDays(item, now), item.title)
            }
            return@consume
        }

        // Query = title + the lead of the article; the spike showed title+lead
        // carries the substance and cross-language retrieval needs no
        // translation step.
        val query = buildString {
            append(item.title)
            item.extractedText?.let { append('\n').append(it.take(queryChars)) }
        }
        val result = library.search(query)
        // Zero books back means the library is empty or serving nothing — a
        // corpus-level fault, not a verdict on this item. Fail loudly and let
        // the retry ladder and DLQ carry it; dropping the item here would turn
        // an outage into a quiet gap in the day's news.
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
