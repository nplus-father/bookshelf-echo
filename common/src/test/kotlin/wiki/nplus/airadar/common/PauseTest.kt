package wiki.nplus.airadar.common

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertFalse(Pause.paused, "PIPELINE_PAUSED must not be set in the test environment")
        assertEquals("${Pause.GAUGE} 0.0", scrape().first { it.startsWith("${Pause.GAUGE} ") })
    }
}
