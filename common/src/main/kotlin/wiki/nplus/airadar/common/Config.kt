package wiki.nplus.airadar.common

/**
 * All runtime configuration comes from environment variables (compose .env).
 *
 * Three states, and the middle one is the whole point:
 *
 *  - **absent or blank** → the default. Blank counts as absent so a commented
 *    -out `.env` line and an empty one behave alike.
 *  - **present but unparseable** → `error()`. It used to fall back to the
 *    default, silently, and that is fine right up until the knob's safe value
 *    and its default are not the same thing. `PIPELINE_PAUSED=True` parsed to
 *    null (Kotlin's strict boolean accepts only lowercase `true`/`false`) and
 *    became `false` — the operator believes the pipeline is stopped while it
 *    keeps polling, keeps buying pro-tier essays and keeps publishing. Same
 *    shape for `DAILY_LLM_BUDGET_USD=0,5`: a European comma becomes the $0.50
 *    default and the cost breaker sits four times higher than intended.
 *  - **present and parseable** → the value.
 *
 * Failing loudly costs a container restart loop on a typo, which is noisy and
 * obvious. The old behaviour cost silence, which is neither.
 */
object Config {
    fun str(name: String, default: String? = null): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: default
            ?: error("Missing required environment variable: $name")

    fun int(name: String, default: Int): Int =
        parse(name, default) { it.toIntOrNull() }

    fun long(name: String, default: Long): Long =
        parse(name, default) { it.toLongOrNull() }

    fun double(name: String, default: Double): Double =
        parse(name, default) { it.toDoubleOrNull() }

    /** Strict on purpose: only lowercase `true`/`false`. `TRUE`, `1` and `yes` are typos, not values. */
    fun bool(name: String, default: Boolean): Boolean =
        parse(name, default) { it.toBooleanStrictOrNull() }

    private fun <T> parse(name: String, default: T, convert: (String) -> T?): T =
        parseOrThrow(name, System.getenv(name), default, convert)

    /**
     * The decision, split from the lookup so it can be tested: a JVM cannot set
     * an environment variable for its own process, and this is the half that
     * has actually been getting things wrong.
     */
    internal fun <T> parseOrThrow(name: String, raw: String?, default: T, convert: (String) -> T?): T {
        val value = raw?.takeIf { it.isNotBlank() } ?: return default
        return convert(value.trim())
            ?: error(
                "Environment variable $name is set to \"$value\", which is not a valid " +
                    "${default!!::class.simpleName?.lowercase()}. Fix the value or unset it to use the default.",
            )
    }
}
