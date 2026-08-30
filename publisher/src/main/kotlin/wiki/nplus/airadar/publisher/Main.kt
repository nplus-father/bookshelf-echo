package wiki.nplus.airadar.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.LibraryClient
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.Settings
import wiki.nplus.airadar.common.StageMessage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

private val log = LoggerFactory.getLogger("publisher")

fun main() = wiki.nplus.airadar.common.App.main("publisher") {
    val registry = wiki.nplus.airadar.common.Metrics.start("publisher", 9104)
    val repo = ItemRepository(Db.dataSource("publisher"))
    val contentDir = Path.of(Config.str("CONTENT_DIR", "out/content"))
    val publishDigest = Config.bool("PUBLISH_DIGEST", false)
    val connection = Rabbit.connect("publisher")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)

    val http = java.net.http.HttpClient.newHttpClient()
    val library = LibraryClient.fromEnv(http)
    val chapterChars = Settings.essayChapterChars

    fun quoteSources(essay: ItemRepository.EssayRow): List<QuoteAnnotator.Source> = runCatching {
        Json.parseToJsonElement(essay.booksJson).jsonArray.mapNotNull { element ->
            val b = element.jsonObject
            val chapterId = b["chapter_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val text = library.chapter(chapterId, chapterChars) ?: return@mapNotNull null
            QuoteAnnotator.Source(
                bookTitle = b["book_title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                chapterTitle = b["chapter_title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                chapterId = chapterId,
                text = text,
            )
        }
    }.getOrElse {
        log.warn("quote attribution skipped for essay {}: {}", essay.day, it.toString())
        emptyList()
    }

    fun publishEssay(itemId: Long) {
        val essay = repo.essayByItem(itemId) ?: error("essay message for item $itemId but no essay row")
        val item = repo.findItem(itemId) ?: error("item $itemId not found")
        val digest = repo.digestForItem(itemId)
        val match = repo.matchFor(itemId)
        val annotated = essay.copy(essayMd = QuoteAnnotator.annotate(essay.essayMd, quoteSources(essay)))
        val target = contentDir.resolve("essays/${essay.day}.md")
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            EssayRenderer.render(
                annotated, item, digest?.summaryEn, digest?.category, match?.booksJson, match?.passagesJson,
            ),
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

        val weekStart = day.with(java.time.DayOfWeek.MONDAY)
        val week = java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()
        val isoWeekLabel = "%d-W%02d".format(day.get(java.time.temporal.WeekFields.ISO.weekBasedYear()), day.get(week))
        val weekItems = repo.digestsForRange(weekStart, weekStart.plusDays(7))
        val weeklyTarget = contentDir.resolve("weekly/$isoWeekLabel.md")
        Files.createDirectories(weeklyTarget.parent)
        Files.writeString(weeklyTarget, DigestRenderer.renderWeekly(weekStart, isoWeekLabel, weekItems))

        repo.transition(itemId, ItemState.DIGESTED, ItemState.PUBLISHED)
        repo.recordPublish("DAILY", target.toString(), null, items.size, "SUCCESS")
        log.info("published {} ({} items) + weekly {}", target, items.size, isoWeekLabel)
    }

    val snapshotJob = SnapshotJob(repo, contentDir, java.net.http.HttpClient.newHttpClient())
    val snapshotMinutes = Settings.snapshotIntervalMinutes
    val settleSeconds = Config.int("SNAPSHOT_SETTLE_SECONDS", 45)

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

internal fun pageDay(item: ItemRepository.ItemRow): LocalDate =
    LocalDate.ofInstant((item.digestedAt ?: item.receivedAt).toInstant(), ZoneOffset.UTC)
