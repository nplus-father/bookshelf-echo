package wiki.nplus.airadar.enricher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.ItemEnvelope
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemRepository.InsertOutcome
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.Settings
import wiki.nplus.airadar.common.StageMessage
import wiki.nplus.airadar.common.UrlCanonicalizer

private val log = LoggerFactory.getLogger("enricher")
private val json = Json { ignoreUnknownKeys = true }

fun main() = wiki.nplus.airadar.common.App.main("enricher") {
    val registry = wiki.nplus.airadar.common.Metrics.start("enricher", 9102)
    val repo = ItemRepository(Db.dataSource("enricher"))
    val fetcher = ContentFetcher()
    val maxChars = Settings.fetchMaxChars
    val connection = Rabbit.connect("enricher")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)

    /**
     * Fetch, persist, advance, hand off. Every step is idempotent (saveContent
     * upserts, transition is conditional, the digester no-ops on a state it has
     * already left), so this is safe to re-run on a redelivered item.
     */
    fun enrich(itemId: Long, envelope: ItemEnvelope) {
        // Sources that already deliver the full body (guardian: Content API
        // show-fields=bodyText) skip scraping entirely — the API text beats
        // anything Jsoup can extract, and there is no paywall/bot-wall risk.
        val supplied = envelope.rawPayload?.get("bodyText")
            ?.let { (it as? JsonPrimitive)?.content }?.takeUnless { it.isBlank() }
        val fetched = if (supplied != null) {
            ContentFetcher.Fetched("FULL", supplied.take(maxChars))
        } else {
            fetcher.fetch(envelope.url)
        }
        repo.saveContent(itemId, fetched.level, fetched.text)
        if (repo.transition(itemId, ItemState.RECEIVED, ItemState.ENRICHED)) {
            // Next stop is the resonance gate (ADR-010), not the digester.
            Rabbit.publish(channel, "", RabbitTopology.MATCH_QUEUE, StageMessage(itemId).encode())
            log.info("enriched item {} ({}): {}", itemId, fetched.level, envelope.title)
        }
    }

    log.info("enricher: consuming {}", RabbitTopology.INGEST_QUEUE)
    Rabbit.consume(channel, RabbitTopology.INGEST_QUEUE, registry) { body ->
        val envelope = json.decodeFromString(ItemEnvelope.serializer(), body)
        val canonical = UrlCanonicalizer.canonicalize(envelope.url)
        val hash = UrlCanonicalizer.contentHash(envelope.title, envelope.url)

        when (val outcome = repo.insertReceived(envelope, canonical, hash)) {
            // Still RECEIVED means an earlier delivery committed the insert and
            // died before handing off to digest.q — acking here would strand the
            // item forever, so resume it (ADR-003: redelivery must converge).
            is InsertOutcome.AlreadySeen ->
                if (outcome.state == ItemState.RECEIVED.name) {
                    log.info("resuming interrupted enrichment of item {}: {}", outcome.itemId, envelope.title)
                    enrich(outcome.itemId, envelope)
                } else {
                    log.debug("already seen: {}/{} ({})", envelope.source, envelope.externalId, outcome.state)
                }

            is InsertOutcome.DuplicateContent ->
                log.info("cross-source duplicate of item {}: {}", outcome.duplicateOf, envelope.title)

            is InsertOutcome.New -> enrich(outcome.itemId, envelope)
        }
    }
}
