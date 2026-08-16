package wiki.nplus.airadar.common

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pause is only half implemented here; the other half is a PromQL string in
 * another repo (`nplus-gitops/workloads/monitoring-rules/bookshelf-echo-alerts.yaml`:
 * `BookshelfEchoEssayStale`'s `unless`, and `BookshelfEchoPaused`'s whole expr).
 * Nothing on this side breaks if the metric is renamed or stops being exported —
 * the rule simply never matches again, the suppression quietly stops working,
 * and the next deliberate stop pages on day three. Only a test can hold the two
 * halves together, so these assert the wire format, not the Kotlin.
 */
class PauseTest {

    private fun scrape(): List<String> {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        Pause.register("test", registry)
        return registry.scrape().lines()
    }

    @Test
    fun `the gauge reaches the exposition under the name the alert rules match`() {
        assertEquals("airadar_pipeline_paused", Pause.GAUGE)
        assertTrue(
            scrape().any { it.startsWith("${Pause.GAUGE} ") },
            "no `${Pause.GAUGE} <value>` line in the scrape — micrometer renamed or suffixed it, " +
                "and the alert rules match the raw name",
        )
    }

    @Test
    fun `an unset PIPELINE_PAUSED exports 0, not an absent series`() {
        // The default has to be a real 0 rather than nothing: `max(...) == 1`
        // over an absent series is an empty vector, which suppresses nothing —
        // correct for a full outage, but it would make a paused-but-unscraped
        // app indistinguishable from a dead one. Exporting 0 while running is
        // what makes the 1 mean something.
        assertFalse(Pause.paused, "PIPELINE_PAUSED must not be set in the test environment")
        assertEquals("${Pause.GAUGE} 0.0", scrape().first { it.startsWith("${Pause.GAUGE} ") })
    }
}
