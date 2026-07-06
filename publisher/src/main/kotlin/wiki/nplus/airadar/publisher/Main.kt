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
 * M1 scope: regenerate the daily markdown into CONTENT_DIR on every digested
 * item. Git commit/push of the site repo (ADR-005) lands with the site repo
 * itself; enable with PUBLISH_GIT=true once CONTENT_DIR is a git checkout.
 */
fun main() {
    val registry = wiki.nplus.airadar.common.Metrics.start("publisher", 9104)
    val repo = ItemRepository(Db.dataSource("publisher"))
    val contentDir = Path.of(Config.str("CONTENT_DIR", "out/content"))
    val publishGit = Config.bool("PUBLISH_GIT", false)
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

        val day = LocalDate.ofInstant(item.receivedAt.toInstant(), ZoneOffset.UTC)
        val items = repo.digestsForDay(day)
        val target = contentDir.resolve("daily/$day.md")
        Files.createDirectories(target.parent)
        Files.writeString(target, DigestRenderer.renderDaily(day, items))

        val commit = if (publishGit) gitCommitAndPush(contentDir, day) else null
        repo.transition(itemId, ItemState.DIGESTED, ItemState.PUBLISHED)
        repo.recordPublish("DAILY", target.toString(), commit, items.size, "SUCCESS")
        log.info("published {} ({} items{})", target, items.size, commit?.let { ", commit $it" } ?: "")
    }
}

private fun gitCommitAndPush(contentDir: Path, day: LocalDate): String? {
    fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).directory(contentDir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out.trim()
    }
    run("git", "add", "-A")
    val (commitCode, _) = run("git", "commit", "-m", "digest: $day")
    if (commitCode != 0) return null // nothing new to commit
    val (pushCode, pushOut) = run("git", "push")
    if (pushCode != 0) {
        // Pages/remote hiccup (deploy throttling included) → let the ladder retry.
        throw wiki.nplus.airadar.common.RetryableFailure("git push failed: ${pushOut.take(300)}")
    }
    return run("git", "rev-parse", "--short", "HEAD").second
}
