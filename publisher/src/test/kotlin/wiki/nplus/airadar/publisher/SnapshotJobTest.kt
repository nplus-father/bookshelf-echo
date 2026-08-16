package wiki.nplus.airadar.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wiki.nplus.airadar.common.ItemRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The snapshot is a cross-repo contract: this publisher writes it, the site's
 * dashboard reads it, and the site degrades silently on an unrecognised field
 * (every read is `snap?.x ?? default`). A rename on this side would therefore
 * ship green on both sides and appear only as a panel missing from the page.
 *
 * These assertions are about key names and nesting, not about values — they
 * exist so that renaming a key breaks a test rather than a dashboard. The
 * matching read side is `bookshelf-echo-site/src/lib/snapshot.ts`.
 */
class SnapshotJobTest {

    private val now = Instant.parse("2026-07-21T03:00:00Z")

    private fun render(
        byPurpose: List<ItemRepository.LlmTodayRow> = listOf(
            ItemRepository.LlmTodayRow("ESSAY", "gemini-2.5-pro", 0.1832, 48210, 9120, 1),
            ItemRepository.LlmTodayRow("DIGEST", "gemini-2.5-flash", 0.0421, 210400, 18300, 9),
        ),
        paused: Boolean = false,
    ) = Json.parseToJsonElement(
        SnapshotJob.render(
            now = now,
            queues = emptyList(),
            items = mapOf("PUBLISHED" to 7, "FAILED" to 1),
            llm = ItemRepository.LlmToday(0.2253, 258610, 27420, 10),
            byPurpose = byPurpose,
            shortlistPending = 3,
            receivedLast24h = 4,
            paused = paused,
        ),
    ).jsonObject

    @Test
    fun `top-level keys are the ones the dashboard reads`() {
        val snap = render()
        assertEquals(
            setOf(
                "capturedAt", "paused", "snapshotIntervalMinutes", "queues", "items", "llmToday",
                "llmTodayByPurpose", "limits", "shortlist", "receivedLast24h",
            ),
            snap.keys,
        )
        assertEquals("2026-07-21T03:00:00Z", snap["capturedAt"]!!.jsonPrimitive.content)
        assertEquals(4, snap["receivedLast24h"]!!.jsonPrimitive.int())
    }

    /**
     * A paused pipeline and a dead one publish identical numbers — flat queues,
     * no digests, no essay. The dashboard can only tell them apart if the
     * snapshot says so, and the key has to be present in BOTH states: a field
     * that appears only while paused is indistinguishable from an old publisher
     * that never had it, which is how the reader ends up guessing again.
     */
    @Test
    fun `paused is always present and reports the declared stop`() {
        assertEquals(false, render(paused = false)["paused"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, render(paused = true)["paused"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `llmToday carries the totals, llmTodayByPurpose itemises them`() {
        val snap = render()
        assertEquals(
            setOf("costUsd", "inputTokens", "outputTokens", "calls"),
            snap["llmToday"]!!.jsonObject.keys,
        )

        val rows = snap["llmTodayByPurpose"]!!.jsonArray
        assertEquals(2, rows.size)
        // Every row names both what the money bought and which model spent it —
        // the whole point of the breakdown.
        rows.forEach { row ->
            assertEquals(
                setOf("purpose", "model", "costUsd", "inputTokens", "outputTokens", "calls"),
                row.jsonObject.keys,
            )
        }
        assertEquals("ESSAY", rows[0].jsonObject["purpose"]!!.jsonPrimitive.content)
        assertEquals("gemini-2.5-pro", rows[0].jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `limits echo the gates the dashboard measures spend against`() {
        val limits = render()["limits"]!!.jsonObject
        assertEquals(
            setOf("dailyBudgetUsd", "dailyDigestLimit", "shortlistMaxPerDay", "matchNoResonanceDistance"),
            limits.keys,
        )
    }

    @Test
    fun `a day with no LLM calls still emits the breakdown key`() {
        // The site renders the breakdown section only when the array is
        // non-empty; the key must exist regardless, or "no spend yet" becomes
        // indistinguishable from "old publisher version".
        val snap = render(byPurpose = emptyList())
        assertTrue(snap.containsKey("llmTodayByPurpose"))
        assertEquals(0, snap["llmTodayByPurpose"]!!.jsonArray.size)
    }

    /**
     * config/rabbitmq/rabbitmq.conf sets `management.path_prefix = /rabbitmq`
     * so nplus-infra's nginx can mount the UI under /rabbitmq/. That moves the
     * HTTP API too. This client asked for the un-prefixed path and got 404 on
     * every interval — swallowed into a WARN, the snapshot still written, the
     * queue panels silently empty. The prefix belongs in the URL, and the only
     * thing that can notice it going missing again is a test.
     */
    @Test
    fun `queue stats url carries the management path prefix`() {
        assertEquals(
            "/rabbitmq/api/queues",
            SnapshotJob.queuesUri("rabbitmq", 15672, "/rabbitmq").path,
        )
    }

    @Test
    fun `a trailing slash in the prefix does not double up`() {
        assertEquals(
            "/rabbitmq/api/queues",
            SnapshotJob.queuesUri("rabbitmq", 15672, "/rabbitmq/").path,
        )
    }

    @Test
    fun `an empty prefix still yields the plain api path`() {
        // A broker without path_prefix is a valid deployment; only the default
        // has to stay in sync with rabbitmq.conf.
        assertEquals("/api/queues", SnapshotJob.queuesUri("rabbitmq", 15672, "").path)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int() = content.toInt()
}
