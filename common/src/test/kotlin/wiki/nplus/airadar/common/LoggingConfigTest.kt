package wiki.nplus.airadar.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingConfigTest {

    private val context get() = LoggerFactory.getILoggerFactory() as LoggerContext

    @Test
    fun `the config is on the classpath and root is not DEBUG`() {
        assertEquals(Level.INFO, context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level)
    }

    @Test
    fun `infrastructure heartbeats are muted`() {
        listOf("com.zaxxer.hikari", "com.rabbitmq", "org.postgresql").forEach { name ->
            val level = context.getLogger(name).level
            assertEquals(Level.WARN, level, "$name should be muted to WARN, was $level")
        }
    }

    @Test
    fun `timestamps carry the date and are UTC`() {
        val pattern = javaClass.getResource("/logback.xml")!!.readText()
        assertTrue(pattern.contains("yyyy-MM-dd"), "log pattern must include the date")
        assertTrue(pattern.contains("UTC"), "log pattern must render in UTC")
    }
}
