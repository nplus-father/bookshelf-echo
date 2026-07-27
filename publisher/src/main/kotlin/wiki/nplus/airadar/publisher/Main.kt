package wiki.nplus.airadar.publisher

import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.StageMessage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

private val log = LoggerFactory.getLogger("publisher")

/**
 * Regenerates the daily and weekly markdown into CONTENT_DIR on every digested
 * item. Delivery to the site repo (ADR-005) is NOT done here: the site-publisher
 * compose sidecar owns the git commit/push, which keeps this image git-free.
 * CONTENT_DIR is therefore a plain directory, never a checkout.
 */
fun main() = wiki.nplus.airadar.common.App.main("publisher") {
    val registry = wiki.nplus.airadar.common.Metrics.start("publisher", 9104)
    val repo = ItemRepository(Db.dataSource("publisher"))
    val contentDir = Path.of(Config.str("CONTENT_DIR", "out/content"))
    val publishDigest = Config.bool("PUBLISH_DIGEST", false)
    val connection = Rabbit.connect("publisher")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)

    /** The daily essay (news-echo): one markdown file per day under essays/. */
    fun publishEssay(itemId: Long) {
        val essay = repo.essayByItem(itemId) ?: error("essay message for item $itemId but no essay row")
        val item = repo.findItem(itemId) ?: error("item $itemId not found")
        val digest = repo.digestForItem(itemId)
        // The retrieval payload carries each book's category and author; the
        // essayist's own book list does not. Absent (essay from before the
        // matcher, or a purged match) just means a less groupable frontmatter.
        val matchBooks = repo.matchFor(itemId)?.booksJson
        val target = contentDir.resolve("essays/${essay.day}.md")
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            EssayRenderer.render(essay, item, digest?.summaryEn, digest?.category, matchBooks),
        )
        repo.recordPublish("ESSAY", target.toString(), null, 1, "SUCCESS")
        log.info("published essay {} (item {}): {}", target, itemId, essay.title)
    }

    log.info("publisher: consuming {} → {}", RabbitTopology.PUBLISH_QUEUE, contentDir.toAbsolutePath())
    Rabbit.consume(channel, RabbitTopology.PUBLISH_QUEUE, registry) { body ->
        val message = StageMessage.decode(body)
        if (message.kind == "essay") {
            publishEssay(message.itemId)
            return@consume
        }
        val itemId = message.itemId
        val item = repo.findItem(itemId) ?: error("item $itemId not found")

        // Digest pages are retired from the site (2026-07-18): the product is
        // the daily essay, and the Highlights/Also-seen list stops being the
        // storefront. The digesting itself stays — the curator ranks on it —
        // so the item still advances to PUBLISHED; only the markdown stops.
        if (!publishDigest) {
            repo.transition(itemId, ItemState.DIGESTED, ItemState.PUBLISHED)
            repo.recordPublish("DAILY", "(digest publishing disabled)", null, 0, "SKIPPED")
            return@consume
        }

        val day = pageDay(item)
        val items = repo.digestsForDay(day)
        val target = contentDir.resolve("daily/$day.md")
        Files.createDirectories(target.parent)
        Files.writeString(target, DigestRenderer.renderDaily(day, items))

        // The current ISO week's rollup is regenerated alongside the daily —
        // same idempotency-by-regeneration strategy, no scheduler needed.
        val weekStart = day.with(java.time.DayOfWeek.MONDAY)
        val week = java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()
        val isoWeekLabel = "%d-W%02d".format(day.get(java.time.temporal.WeekFields.ISO.weekBasedYear()), day.get(week))
        val weekItems = repo.digestsForRange(weekStart, weekStart.plusDays(7))
        val weeklyTarget = contentDir.resolve("weekly/$isoWeekLabel.md")
        Files.createDirectories(weeklyTarget.parent)
        Files.writeString(weeklyTarget, DigestRenderer.renderWeekly(weekStart, isoWeekLabel, weekItems))

        repo.transition(itemId, ItemState.DIGESTED, ItemState.PUBLISHED)
        // git_commit stays null: the sidecar commits, so the hash is not known here.
        repo.recordPublish("DAILY", target.toString(), null, items.size, "SUCCESS")
        log.info("published {} ({} items) + weekly {}", target, items.size, isoWeekLabel)
    }

    // Snapshots start only once our own consumer is registered — basicConsume is a
    // synchronous RPC, so by here the broker reports publish.q with consumers=1.
    // Started before it, the first capture always recorded our own queue as
    // consumer-less, and at an hourly cadence that false "stalled" reading sat on
    // the public dashboard for an hour after every deploy. The settle delay covers
    // the sibling apps, which compose restarts alongside us.
    val snapshotJob = SnapshotJob(repo, contentDir, java.net.http.HttpClient.newHttpClient())
    val snapshotMinutes = Config.int("SNAPSHOT_INTERVAL_MINUTES", 60)
    val settleSeconds = Config.int("SNAPSHOT_SETTLE_SECONDS", 45)

    // When the snapshot loop stops producing, nothing says so: the dashboard
    // keeps showing the last file it received, and a stale snapshot looks
    // exactly like a healthy one. That is how the publisher went unnoticed for
    // 12 hours on 2026-07-19 while the rest of the pipeline was fine. This gauge
    // is the alertable version of "the snapshot is old" —
    // `time() - airadar_snapshot_last_success_timestamp_seconds`. A counter
    // beside it separates "failing loudly" from "thread died".
    val lastSnapshotSuccess = java.util.concurrent.atomic.AtomicLong(0)
    io.micrometer.core.instrument.Gauge
        .builder("airadar_snapshot_last_success_timestamp_seconds", lastSnapshotSuccess) { it.get().toDouble() }
        .register(registry)
    val snapshotFailures = registry.counter("airadar_snapshot_failures_total")

    kotlin.concurrent.thread(isDaemon = true, name = "metrics-snapshot") {
        Thread.sleep(settleSeconds * 1000L)
        while (true) {
            runCatching { snapshotJob.capture(java.time.Instant.now()) }
                .onSuccess { lastSnapshotSuccess.set(System.currentTimeMillis() / 1000) }
                .onFailure {
                    snapshotFailures.increment()
                    log.warn("snapshot failed: {}", it.toString())
                }
            Thread.sleep(snapshotMinutes * 60_000L)
        }
    }
}

/**
 * Which daily page an item belongs on: the UTC day its DIGEST was produced,
 * never the day it was received.
 *
 * The page's contents come from `digestsForDay()`, which selects on
 * `digests.created_at`. Keying the filename off a different clock (received_at)
 * writes day D's digests onto whichever page the triggering item happened to
 * arrive on, and the daily cap (ADR-007) drives those two clocks apart by
 * design — an item can sit ENRICHED for days before it is digested. A day whose
 * digests share a received_at day with no item would then never get its page
 * written at all.
 *
 * received_at is only a fallback for the impossible case of an undigested item
 * on publish.q.
 */
internal fun pageDay(item: ItemRepository.ItemRow): LocalDate =
    LocalDate.ofInstant((item.digestedAt ?: item.receivedAt).toInstant(), ZoneOffset.UTC)
