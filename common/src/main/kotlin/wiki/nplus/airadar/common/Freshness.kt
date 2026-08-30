package wiki.nplus.airadar.common

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

object Freshness {
    fun ageDays(publishedAt: OffsetDateTime?, receivedAt: OffsetDateTime, now: Instant): Long =
        Duration.between((publishedAt ?: receivedAt).toInstant(), now).toDays().coerceAtLeast(0)

    fun isStale(publishedAt: OffsetDateTime?, receivedAt: OffsetDateTime, now: Instant, maxAgeDays: Long): Boolean =
        maxAgeDays > 0 && ageDays(publishedAt, receivedAt, now) > maxAgeDays

    fun isStale(item: ItemRepository.ItemRow, now: Instant, maxAgeDays: Long): Boolean =
        isStale(item.publishedAt, item.receivedAt, now, maxAgeDays)

    fun ageDays(item: ItemRepository.ItemRow, now: Instant): Long =
        ageDays(item.publishedAt, item.receivedAt, now)
}
