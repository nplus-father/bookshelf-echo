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
    // Same host/port pair the AMQP connection uses: in a container these point
    // at the `rabbitmq` service, not at this process's own loopback.
    private val mgmtHost = Config.str("RABBITMQ_HOST", "127.0.0.1")
    private val mgmtPort = Config.int("RABBITMQ_MGMT_PORT", 15672)

    /**
     * Must match `management.path_prefix` in config/rabbitmq/rabbitmq.conf —
     * which, since 2026-07-21, is unset. The broker serves the management API
     * at the plain `/api/...` again, so the default here is empty.
     *
     * History worth keeping: the prefix existed only so nplus-infra's nginx
     * could mount the UI under https://nplus.space/rabbitmq/. It moved the
     * HTTP API along with the UI, and this client kept asking for the
     * un-prefixed path — a 404 every interval, swallowed into a WARN, the
     * snapshot still written, the dashboard's queue panels quietly empty.
     * Nothing ever turned red. The UI now lives at its own subdomain
     * (https://rabbitmq.nplus.space/), so the prefix — and that whole class
     * of bug — is gone rather than merely fixed.
     *
     * The default has to be empty rather than overridden by env: [Config.str]
     * treats a blank value as absent and falls back, so `RABBITMQ_MGMT_PATH_PREFIX=`
     * would silently restore `/rabbitmq` and re-arm the exact bug above.
     */
    private val mgmtPrefix = Config.str("RABBITMQ_MGMT_PATH_PREFIX", "").trimEnd('/')

    private val auth = Base64.getEncoder().encodeToString(
        "${Config.str("RABBITMQ_USER", "airadar")}:${Config.str("RABBITMQ_PASSWORD")}".toByteArray(),
    )

    fun capture(now: Instant): String {
        val snapshot = render(
            now = now,
            queues = queueStats(),
            items = repo.stateCounts(),
            llm = repo.llmToday(),
            byPurpose = repo.llmTodayByPurpose(),
            shortlistPending = repo.shortlistPending(Config.int("SHORTLIST_TTL_DAYS", 7)).size,
            receivedLast24h = repo.receivedLast24h(),
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
        /**
         * Kept pure and separate from the sending for the same reason [render]
         * is: the prefix bug below was invisible at runtime (404 → WARN →
         * empty panels), so the guard has to be a test, not a log line.
         */
        fun queuesUri(host: String, port: Int, prefix: String): URI = URI.create(
            "http://$host:$port${prefix.trimEnd('/')}" +
                "/api/queues?columns=name,messages,messages_ready,messages_unacknowledged,consumers",
        )

        /**
         * The snapshot's shape, kept pure and separate from the gathering so a
         * test can assert it without a database. These key names are a
         * cross-repo contract: the bookshelf-echo-site dashboard reads them and
         * degrades silently on a field it does not recognise, so a rename here
         * would otherwise ship green on both sides and surface only as a panel
         * quietly missing from the page.
         */
        fun render(
            now: Instant,
            queues: List<JsonElement>,
            items: Map<String, Int>,
            llm: ItemRepository.LlmToday,
            byPurpose: List<ItemRepository.LlmTodayRow>,
            shortlistPending: Int,
            receivedLast24h: Int,
        ): String = buildJsonObject {
            put("capturedAt", now.toString())
            // How often this file is supposed to be rewritten. Without it a
            // reader cannot tell a fresh snapshot from a stale one — the
            // publisher went silent for 12 hours on 2026-07-19 and the
            // dashboard looked exactly as healthy as ever. The cadence is the
            // publisher's own, so it publishes it rather than making the site
            // hardcode a guess.
            put("snapshotIntervalMinutes", Config.int("SNAPSHOT_INTERVAL_MINUTES", 60))
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
            // The same bill, itemised: which purpose and which model spent it.
            // A single total cannot distinguish "the essay tier ran" from "a
            // digest storm", and it never names the service doing the work —
            // the dashboard reads this to show both.
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
            // The digester's gates, echoed so a reader of the snapshot can tell
            // "spent $0.12" from "spent $0.12 of $0.50" — a spend figure without
            // its limit says nothing about whether the breaker is close to
            // tripping. Same env vars the digester reads (compose gives every
            // app the same .env); reporting only, never enforcement.
            putJsonObject("limits") {
                put("dailyBudgetUsd", Config.double("DAILY_LLM_BUDGET_USD", 0.50))
                put("dailyDigestLimit", Config.int("DAILY_DIGEST_LIMIT", 10))
                put("shortlistMaxPerDay", Config.int("SHORTLIST_MAX_PER_DAY", 3))
                put("matchNoResonanceDistance", Config.double("MATCH_NO_RESONANCE_DISTANCE", 1.10))
            }
            // The selection funnel's live pool (ADR-009): picks awaiting a
            // composition, within TTL.
            putJsonObject("shortlist") {
                put("pendingCount", shortlistPending)
            }
            put("receivedLast24h", receivedLast24h)
        }.toString()
    }
}
