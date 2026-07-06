package wiki.nplus.airadar.enricher

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.ItemEnvelope
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemRepository.InsertOutcome
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.StageMessage
import wiki.nplus.airadar.common.UrlCanonicalizer

private val log = LoggerFactory.getLogger("enricher")
private val json = Json { ignoreUnknownKeys = true }

fun main() {
    val registry = wiki.nplus.airadar.common.Metrics.start("enricher", 9102)
    val repo = ItemRepository(Db.dataSource("enricher"))
    val fetcher = ContentFetcher()
    val connection = Rabbit.connect("enricher")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)

    log.info("enricher: consuming {}", RabbitTopology.INGEST_QUEUE)
    Rabbit.consume(channel, RabbitTopology.INGEST_QUEUE, registry) { body ->
        val envelope = json.decodeFromString(ItemEnvelope.serializer(), body)
        val canonical = UrlCanonicalizer.canonicalize(envelope.url)
        val hash = UrlCanonicalizer.contentHash(envelope.title, envelope.url)

        when (val outcome = repo.insertReceived(envelope, canonical, hash)) {
            is InsertOutcome.AlreadySeen ->
                log.debug("already seen: {}/{}", envelope.source, envelope.externalId)

            is InsertOutcome.DuplicateContent ->
                log.info("cross-source duplicate of item {}: {}", outcome.duplicateOf, envelope.title)

            is InsertOutcome.New -> {
                val fetched = fetcher.fetch(envelope.url)
                repo.saveContent(outcome.itemId, fetched.level, fetched.text)
                if (repo.transition(outcome.itemId, ItemState.RECEIVED, ItemState.ENRICHED)) {
                    Rabbit.publish(channel, "", RabbitTopology.DIGEST_QUEUE, StageMessage(outcome.itemId).encode())
                    log.info("enriched item {} ({}): {}", outcome.itemId, fetched.level, envelope.title)
                }
            }
        }
    }
}
