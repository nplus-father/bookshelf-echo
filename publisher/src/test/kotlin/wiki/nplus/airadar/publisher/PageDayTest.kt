package wiki.nplus.airadar.publisher

import wiki.nplus.airadar.common.ItemRepository.ItemRow
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class PageDayTest {

    private fun item(receivedAt: String, digestedAt: String?) = ItemRow(
        id = 1,
        source = "hn",
        url = "https://example.com/1",
        title = "Item 1",
        state = "DIGESTED",
        receivedAt = OffsetDateTime.parse(receivedAt),
        extractedText = null,
        digestedAt = digestedAt?.let(OffsetDateTime::parse),
    )

    @Test
    fun `page day follows the digest, not the arrival`() {
        // The daily cap can hold an item for days; the page it lands on must be
        // the day its digest was written, which is what digestsForDay() selects.
        val day = pageDay(item(receivedAt = "2026-07-06T23:50:00Z", digestedAt = "2026-07-15T00:05:00Z"))
        assertEquals(LocalDate.of(2026, 7, 15), day)
    }

    @Test
    fun `page day is UTC, not local`() {
        // Digested 07-15 01:00 in +08:00 is still 07-14 in UTC — the day the
        // digests.created_at comparison in digestsForDay() will match.
        val day = pageDay(item(receivedAt = "2026-07-14T00:00:00Z", digestedAt = "2026-07-15T01:00:00+08:00"))
        assertEquals(LocalDate.of(2026, 7, 14), day)
    }

    @Test
    fun `falls back to received_at when there is no digest`() {
        val day = pageDay(item(receivedAt = "2026-07-06T12:00:00Z", digestedAt = null))
        assertEquals(LocalDate.of(2026, 7, 6), day)
    }
}
