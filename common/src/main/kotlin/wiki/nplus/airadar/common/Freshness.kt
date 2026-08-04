package wiki.nplus.airadar.common

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The freshness cutoff (V5), as a decision anyone can ask about before doing
 * work on an item.
 *
 * It used to live only inside the digester, one stop past the matcher — so
 * every stale item still paid for a Voyage query embedding and a `matches` row
 * before being dropped. On 2026-08-04 the ledger read 452 STALE against 437
 * PUBLISHED: close to half the traffic walked the whole path to be thrown away
 * at the end of it. The matcher now asks first, and the digester keeps asking,
 * because `digest.q` still holds messages that were fresh when they were
 * published there.
 *
 * The clock is the news's own publication time; `received_at` only stands in
 * when the feed gives no date.
 */
object Freshness {
    /** Days between the item's own date and [now]; negative dates count as 0. */
    fun ageDays(publishedAt: OffsetDateTime?, receivedAt: OffsetDateTime, now: Instant): Long =
        Duration.between((publishedAt ?: receivedAt).toInstant(), now).toDays().coerceAtLeast(0)

    /** [maxAgeDays] of 0 or less disables the cutoff entirely. */
    fun isStale(publishedAt: OffsetDateTime?, receivedAt: OffsetDateTime, now: Instant, maxAgeDays: Long): Boolean =
        maxAgeDays > 0 && ageDays(publishedAt, receivedAt, now) > maxAgeDays

    fun isStale(item: ItemRepository.ItemRow, now: Instant, maxAgeDays: Long): Boolean =
        isStale(item.publishedAt, item.receivedAt, now, maxAgeDays)

    fun ageDays(item: ItemRepository.ItemRow, now: Instant): Long =
        ageDays(item.publishedAt, item.receivedAt, now)
}
