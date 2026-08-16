package wiki.nplus.airadar.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * These assert the *parser*, not the env lookup: the JVM cannot set an
 * environment variable for its own process, so every case here goes through
 * [Config.parseOrThrow] with the string a real `.env` line would have produced.
 * The lookup half is one `System.getenv` call and has never been the problem.
 *
 * What has been the problem is the fallback. Every knob whose safe value equals
 * its default tolerated a silent fallback for a year; `PIPELINE_PAUSED` is the
 * first one where the default is the dangerous side, and "you thought you
 * stopped it" is not a failure mode worth keeping for the sake of leniency.
 */
class ConfigTest {

    @Test
    fun `absent and blank both mean the default`() {
        assertEquals(7, Config.parseOrThrow("X", null, 7) { it.toIntOrNull() })
        assertEquals(7, Config.parseOrThrow("X", "", 7) { it.toIntOrNull() })
        assertEquals(7, Config.parseOrThrow("X", "   ", 7) { it.toIntOrNull() })
    }

    @Test
    fun `a parseable value wins over the default`() {
        assertEquals(3, Config.parseOrThrow("X", "3", 7) { it.toIntOrNull() })
        // Surrounding whitespace is a .env artefact, not a typo.
        assertEquals(3, Config.parseOrThrow("X", " 3 ", 7) { it.toIntOrNull() })
    }

    /**
     * The regression this whole change exists for. Kotlin's strict boolean
     * accepts only lowercase, so every one of these used to become the default
     * `false` — i.e. "not paused" — while the operator believed otherwise.
     */
    @Test
    fun `a boolean typo throws instead of quietly reading as not-paused`() {
        listOf("True", "TRUE", "1", "yes", "on").forEach { typo ->
            val e = assertFailsWith<IllegalStateException>("\"$typo\" must not be accepted") {
                Config.parseOrThrow("PIPELINE_PAUSED", typo, false) { it.toBooleanStrictOrNull() }
            }
            assertTrue(
                e.message!!.contains("PIPELINE_PAUSED") && e.message!!.contains(typo),
                "the message must name the variable and show the offending value: ${e.message}",
            )
        }
        assertEquals(true, Config.parseOrThrow("X", "true", false) { it.toBooleanStrictOrNull() })
        assertEquals(false, Config.parseOrThrow("X", "false", true) { it.toBooleanStrictOrNull() })
    }

    @Test
    fun `a decimal comma throws rather than silently restoring the default budget`() {
        assertFailsWith<IllegalStateException> {
            Config.parseOrThrow("DAILY_LLM_BUDGET_USD", "0,5", 0.50) { it.toDoubleOrNull() }
        }
        assertEquals(1.0, Config.parseOrThrow("DAILY_LLM_BUDGET_USD", "1.00", 0.50) { it.toDoubleOrNull() })
    }

    /** Every value in the live prod .env must survive the new strictness. */
    @Test
    fun `the values prod actually sets still parse`() {
        assertEquals(1.0, Config.parseOrThrow("X", "1.00", 0.50) { it.toDoubleOrNull() })
        assertEquals(60, Config.parseOrThrow("X", "60", 60) { it.toIntOrNull() })
        assertEquals(false, Config.parseOrThrow("X", "false", false) { it.toBooleanStrictOrNull() })
        assertEquals(2L, Config.parseOrThrow("X", "2", 3L) { it.toLongOrNull() })
    }
}
