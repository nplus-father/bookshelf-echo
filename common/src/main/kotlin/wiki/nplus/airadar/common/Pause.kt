package wiki.nplus.airadar.common

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

object Pause {
    const val GAUGE = "airadar_pipeline_paused"

    private val log = LoggerFactory.getLogger(Pause::class.java)

    val paused: Boolean = Config.bool("PIPELINE_PAUSED", false)

    private val state = AtomicInteger(if (paused) 1 else 0)

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
