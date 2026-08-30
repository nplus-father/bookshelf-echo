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
        val snap = render(byPurpose = emptyList())
        assertTrue(snap.containsKey("llmTodayByPurpose"))
        assertEquals(0, snap["llmTodayByPurpose"]!!.jsonArray.size)
    }

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
        assertEquals("/api/queues", SnapshotJob.queuesUri("rabbitmq", 15672, "").path)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int() = content.toInt()
}
