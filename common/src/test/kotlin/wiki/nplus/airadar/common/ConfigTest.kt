package wiki.nplus.airadar.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        assertEquals(3, Config.parseOrThrow("X", " 3 ", 7) { it.toIntOrNull() })
    }

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

    @Test
    fun `the values prod actually sets still parse`() {
        assertEquals(1.0, Config.parseOrThrow("X", "1.00", 0.50) { it.toDoubleOrNull() })
        assertEquals(60, Config.parseOrThrow("X", "60", 60) { it.toIntOrNull() })
        assertEquals(false, Config.parseOrThrow("X", "false", false) { it.toBooleanStrictOrNull() })
        assertEquals(2L, Config.parseOrThrow("X", "2", 3L) { it.toLongOrNull() })
    }
}
