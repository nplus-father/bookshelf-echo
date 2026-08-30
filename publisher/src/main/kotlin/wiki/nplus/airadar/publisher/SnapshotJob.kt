package wiki.nplus.airadar.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.Settings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

class SnapshotJob(private val repo: ItemRepository, private val contentDir: Path, private val http: HttpClient) {
    private val log = LoggerFactory.getLogger(SnapshotJob::class.java)

    private val mgmtHost = Settings.rabbitmqHost
    private val mgmtPort = Config.int("RABBITMQ_MGMT_PORT", 15672)

    private val mgmtPrefix = Config.str("RABBITMQ_MGMT_PATH_PREFIX", "").trimEnd('/')

    private val auth = Base64.getEncoder().encodeToString(
        "${Settings.rabbitmqUser}:${Settings.rabbitmqPassword}".toByteArray(),
    )

    fun capture(now: Instant): String {
        val snapshot = render(
            now = now,
            queues = queueStats(),
            items = repo.stateCounts(),
            llm = repo.llmToday(),
            byPurpose = repo.llmTodayByPurpose(),
            shortlistPending = repo.shortlistPending(Settings.shortlistTtlDays).size,
            receivedLast24h = repo.receivedLast24h(),
            paused = wiki.nplus.airadar.common.Pause.paused,
        )

        repo.saveSnapshot(snapshot)
        val dir = contentDir.resolve("data/metrics")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("latest.json"), snapshot)
        log.info("metrics snapshot captured")
        return snapshot
    }

    private fun queueStats() = try {
        val request = HttpRequest.newBuilder(queuesUri(mgmtHost, mgmtPort, mgmtPrefix))
            .header("Authorization", "Basic $auth").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "management API returned ${response.statusCode()}" }
        Json.parseToJsonElement(response.body()).jsonArray.toList()
    } catch (e: Exception) {
        log.warn("queue stats unavailable: {}", e.toString())
        emptyList()
    }

    companion object {
        fun queuesUri(host: String, port: Int, prefix: String): URI = URI.create(
            "http://$host:$port${prefix.trimEnd('/')}" +
                "/api/queues?columns=name,messages,messages_ready,messages_unacknowledged,consumers",
        )

        fun render(
            now: Instant,
            queues: List<JsonElement>,
            items: Map<String, Int>,
            llm: ItemRepository.LlmToday,
            byPurpose: List<ItemRepository.LlmTodayRow>,
            shortlistPending: Int,
            receivedLast24h: Int,
            paused: Boolean,
        ): String = buildJsonObject {
            put("capturedAt", now.toString())
            put("paused", paused)
            put("snapshotIntervalMinutes", Settings.snapshotIntervalMinutes)
            putJsonArray("queues") {
                queues.forEach { add(it) }
            }
            putJsonObject("items") {
                items.forEach { (state, count) -> put(state, count) }
            }
            putJsonObject("llmToday") {
                put("costUsd", llm.costUsd)
                put("inputTokens", llm.inputTokens)
                put("outputTokens", llm.outputTokens)
                put("calls", llm.calls)
            }
            putJsonArray("llmTodayByPurpose") {
                byPurpose.forEach { row ->
                    addJsonObject {
                        put("purpose", row.purpose)
                        put("model", row.model)
                        put("costUsd", row.costUsd)
                        put("inputTokens", row.inputTokens)
                        put("outputTokens", row.outputTokens)
                        put("calls", row.calls)
                    }
                }
            }
            putJsonObject("limits") {
                put("dailyBudgetUsd", Settings.dailyBudgetUsd)
                put("dailyDigestLimit", Settings.dailyDigestLimit)
                put("shortlistMaxPerDay", Settings.shortlistMaxPerDay)
                put("matchNoResonanceDistance", Settings.matchNoResonanceDistance)
            }
            putJsonObject("shortlist") {
                put("pendingCount", shortlistPending)
            }
            put("receivedLast24h", receivedLast24h)
        }.toString()
    }
}
