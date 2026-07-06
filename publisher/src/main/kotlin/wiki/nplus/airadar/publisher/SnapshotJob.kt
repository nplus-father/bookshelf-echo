package wiki.nplus.airadar.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/**
 * The dashboard's data source (design doc §5, ADR-005): every interval, one
 * JSON snapshot of pipeline health — queue depths from the RabbitMQ management
 * API, item/LLM stats from Postgres — written to CONTENT_DIR/data/metrics/ and
 * kept in metrics_snapshots for history.
 */
class SnapshotJob(private val repo: ItemRepository, private val contentDir: Path, private val http: HttpClient) {
    private val log = LoggerFactory.getLogger(SnapshotJob::class.java)
    private val mgmtPort = Config.int("RABBITMQ_MGMT_PORT", 15672)
    private val auth = Base64.getEncoder().encodeToString(
        "${Config.str("RABBITMQ_USER", "airadar")}:${Config.str("RABBITMQ_PASSWORD")}".toByteArray(),
    )

    fun capture(now: Instant): String {
        val llm = repo.llmToday()
        val snapshot = buildJsonObject {
            put("capturedAt", now.toString())
            putJsonArray("queues") {
                queueStats().forEach { add(it) }
            }
            putJsonObject("items") {
                repo.stateCounts().forEach { (state, count) -> put(state, count) }
            }
            putJsonObject("llmToday") {
                put("costUsd", llm.costUsd)
                put("inputTokens", llm.inputTokens)
                put("outputTokens", llm.outputTokens)
                put("calls", llm.calls)
            }
            put("receivedLast24h", repo.receivedLast24h())
        }.toString()

        repo.saveSnapshot(snapshot)
        val dir = contentDir.resolve("data/metrics")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("latest.json"), snapshot)
        log.info("metrics snapshot captured")
        return snapshot
    }

    private fun queueStats() = try {
        val request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:$mgmtPort/api/queues?columns=name,messages,messages_ready,messages_unacknowledged,consumers"),
        ).header("Authorization", "Basic $auth").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "management API returned ${response.statusCode()}" }
        Json.parseToJsonElement(response.body()).jsonArray.toList()
    } catch (e: Exception) {
        log.warn("queue stats unavailable: {}", e.toString())
        emptyList()
    }
}
