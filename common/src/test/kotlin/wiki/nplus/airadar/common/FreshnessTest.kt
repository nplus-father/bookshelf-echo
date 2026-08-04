package wiki.nplus.airadar.common

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshnessTest {

    private val now = Instant.parse("2026-08-04T00:00:00Z")

    private fun at(iso: String): OffsetDateTime = OffsetDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC)

    @Test
    fun `age comes from the news's own date, not when we received it`() {
        // Received today, but the article is a week old — the feed backfilled it.
        val age = Freshness.ageDays(
            publishedAt = at("2026-07-28T00:00:00Z"),
            receivedAt = at("2026-08-04T00:00:00Z"),
            now = now,
        )
        assertEquals(7, age)
    }

    @Test
    fun `received_at stands in when the feed gives no publication date`() {
        val age = Freshness.ageDays(publishedAt = null, receivedAt = at("2026-08-01T00:00:00Z"), now = now)
        assertEquals(3, age)
    }

    @Test
    fun `age is whole days, so a 3-day cutoff really means 4 days old`() {
        // Duration.toDays() truncates: anything short of a full extra day still
        // reads as 3. Worth knowing before anyone reasons about MATCH_MAX_AGE_DAYS
        // in hours — this is the pre-existing behaviour, kept deliberately.
        val exactly3 = at("2026-08-01T00:00:00Z")
        val almost4 = at("2026-07-31T00:00:01Z")
        val fully4 = at("2026-07-31T00:00:00Z")
        assertFalse(Freshness.isStale(exactly3, exactly3, now, maxAgeDays = 3))
        assertFalse(Freshness.isStale(almost4, almost4, now, maxAgeDays = 3))
        assertTrue(Freshness.isStale(fully4, fully4, now, maxAgeDays = 3))
    }

    @Test
    fun `a zero cutoff disables the check`() {
        val ancient = at("2020-01-01T00:00:00Z")
        assertFalse(Freshness.isStale(ancient, ancient, now, maxAgeDays = 0))
    }

    @Test
    fun `a future publication date is not negative age`() {
        val tomorrow = at("2026-08-05T00:00:00Z")
        assertEquals(0, Freshness.ageDays(tomorrow, tomorrow, now))
        assertFalse(Freshness.isStale(tomorrow, tomorrow, now, maxAgeDays = 3))
    }
}
