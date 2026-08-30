package wiki.nplus.airadar.producers

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemEnvelope
import wiki.nplus.airadar.common.Pause
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import java.net.http.HttpClient
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("producers")
private val json = Json { encodeDefaults = true }

fun main() = wiki.nplus.airadar.common.App.main("producers") {
    val registry = wiki.nplus.airadar.common.Metrics.start("producers", 9101)
    val connection = Rabbit.connect("producers")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)

    if (Pause.paused) {
        log.warn("producers: paused, polling no source")
        java.util.concurrent.CountDownLatch(1).await()
    }

    val http = HttpClient.newHttpClient()

    data class Source(val name: String, val defaultIntervalMinutes: Int, val poll: () -> List<ItemEnvelope>)

    val all = listOf(
        Source("hn", 60, HnSource(http)::poll),
        Source("arxiv", 1440, ArxivSource(http)::poll),
        Source("gh-trending", 1440, GhTrendingSource(http)::poll),
        Source("blogs", 240, BlogsSource(http)::poll),
        Source("news", 180, NewsSource(http)::poll),
        Source("guardian", 180, GuardianSource(http)::poll),
        Source("reddit", 1440, RedditSource(http)::poll),
    )
    val enabled = Config.str("SOURCES", "guardian").split(',').map { it.trim() }.toSet()
    val sources = all.filter { it.name in enabled }
    if (sources.isEmpty()) {
        error("SOURCES=\"${Config.str("SOURCES", "guardian")}\" matches no source; known: ${all.joinToString { it.name }}")
    }
    log.info("enabled sources: {}", sources.joinToString { it.name })

    val runOnce = Config.bool("RUN_ONCE", false)
    runBlocking {
        sources.forEach { (name, defaultInterval, poll) ->
            val interval = Config.int("${name.uppercase().replace('-', '_')}_INTERVAL_MINUTES", defaultInterval)
            launch {
                while (true) {
                    try {
                        val items = poll()
                        items.forEach { item ->
                            Rabbit.publish(
                                channel,
                                RabbitTopology.INGEST_EXCHANGE,
                                RabbitTopology.routingKey(item.source),
                                json.encodeToString(ItemEnvelope.serializer(), item),
                            )
                        }
                        registry.counter("airadar_produced_total", "source", name).increment(items.size.toDouble())
                        log.info("{}: published {} items", name, items.size)
                    } catch (e: Exception) {
                        registry.counter("airadar_poll_failures_total", "source", name).increment()
                        log.warn("{}: poll failed, next cadence will retry: {}", name, e.toString())
                    }
                    if (runOnce) break
                    delay(interval.minutes)
                }
            }
        }
    }
    channel.close()
    connection.close()
    log.info("producers: done (RUN_ONCE mode)")
}
