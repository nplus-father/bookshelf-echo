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
    val connection = Rabbit.connect("publisher")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)

    val snapshotJob = SnapshotJob(repo, contentDir, java.net.http.HttpClient.newHttpClient())
    val snapshotMinutes = Config.int("SNAPSHOT_INTERVAL_MINUTES", 60)
    kotlin.concurrent.thread(isDaemon = true, name = "metrics-snapshot") {
        while (true) {
            runCatching { snapshotJob.capture(java.time.Instant.now()) }
                .onFailure { log.warn("snapshot failed: {}", it.toString()) }
            Thread.sleep(snapshotMinutes * 60_000L)
        }
    }

    log.info("publisher: consuming {} → {}", RabbitTopology.PUBLISH_QUEUE, contentDir.toAbsolutePath())
    Rabbit.consume(channel, RabbitTopology.PUBLISH_QUEUE, registry) { body ->
        val itemId = StageMessage.decode(body).itemId
        val item = repo.findItem(itemId) ?: error("item $itemId not found")

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
