package wiki.nplus.airadar.producers

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemEnvelope
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import java.net.http.HttpClient
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("producers")
private val json = Json { encodeDefaults = true }

/**
 * One process, one coroutine per source, each on its own cadence.
 * Producer failures are not queue-retried (there is no message yet) — the next
 * scheduled poll is the retry. RUN_ONCE=true does a single pass and exits,
 * for verification.
 */
fun main() {
    val registry = wiki.nplus.airadar.common.Metrics.start("producers", 9101)
    val connection = Rabbit.connect("producers")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    val http = HttpClient.newHttpClient()

    val sources: List<Pair<String, () -> List<ItemEnvelope>>> = listOf(
        "hn" to HnSource(http)::poll,
        // M3: arxiv, gh-trending, blogs (RSS), reddit
    )

    val runOnce = Config.bool("RUN_ONCE", false)
    runBlocking {
        sources.forEach { (name, poll) ->
            val interval = Config.int("${name.uppercase()}_INTERVAL_MINUTES", 60)
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
