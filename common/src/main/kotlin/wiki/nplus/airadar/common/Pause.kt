package wiki.nplus.airadar.common

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The declared stop.
 *
 * Stopping this pipeline used to mean `docker compose stop`, and that fires
 * about a dozen alerts: eight `ScrapeTargetDown` (five apps, the broker, its
 * per-queue scrape, the pg exporter) plus every rule watching a node-exporter
 * textfile metric — those files stay on disk after the sidecar dies and simply
 * go stale, so `SitePublishStale`, `SiteDeployStale` and `MetricsStale` all
 * open up. critical repeats every 4h, so the stop keeps paging for as long as
 * it lasts. The alternative was an Alertmanager silence, which has to be
 * remembered, expires at the worst moment, and blinds real faults meanwhile.
 *
 * PIPELINE_PAUSED is the third option: say it in band. Every process stays up,
 * keeps its metrics endpoint bound and its consumer registered — only the
 * *work* stops. Nothing watching the observability path (snapshot freshness,
 * site push, deploy lag) can tell the difference, which is the point: pausing
 * the product must not disarm the monitoring of the monitoring. The one rule a
 * legitimate pause would trip, `BookshelfEchoEssayStale`, reads [GAUGE] and
 * stands down; `BookshelfEchoPaused` then nags once a day so a forgotten pause
 * cannot become a permanent one.
 *
 * Read once at startup on purpose. Unpausing is `.env` + `docker compose up -d`,
 * the same shape as every other knob here (LOG_LEVEL, the model tiers), and a
 * flag that cannot flip mid-flight is a flag no half-processed item can
 * straddle.
 */
object Pause {
    /** The gauge the alert rules stand down on; 1 while paused. */
    const val GAUGE = "airadar_pipeline_paused"

    private val log = LoggerFactory.getLogger(Pause::class.java)

    val paused: Boolean = Config.bool("PIPELINE_PAUSED", false)

    private val state = AtomicInteger(if (paused) 1 else 0)

    /**
     * Registered from [Metrics.start] so every app exports it without five
     * identical call sites — one series per instance, and the rules aggregate
     * with `max()`. Absent from every instance means nothing is running at all:
     * a full stop, not a declared pause, and correctly not suppressed.
     */
    fun register(appName: String, registry: MeterRegistry) {
        Gauge.builder(GAUGE, state) { it.get().toDouble() }
            .description("1 = PIPELINE_PAUSED, work suspended on purpose; 0 = running")
            .register(registry)
        if (paused) {
            log.warn(
                "{}: PIPELINE_PAUSED=true — staying up and scrapeable, doing no work. " +
                    "Unset it in .env and `docker compose up -d` to resume.",
                appName,
            )
        }
    }
}
