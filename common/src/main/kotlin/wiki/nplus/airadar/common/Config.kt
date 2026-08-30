package wiki.nplus.airadar.common

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

    fun bool(name: String, default: Boolean): Boolean =
        parse(name, default) { it.toBooleanStrictOrNull() }

    private fun <T> parse(name: String, default: T, convert: (String) -> T?): T =
        parseOrThrow(name, System.getenv(name), default, convert)

    internal fun <T> parseOrThrow(name: String, raw: String?, default: T, convert: (String) -> T?): T {
        val value = raw?.takeIf { it.isNotBlank() } ?: return default
        return convert(value.trim())
            ?: error(
                "Environment variable $name is set to \"$value\", which is not a valid " +
                    "${default!!::class.simpleName?.lowercase()}. Fix the value or unset it to use the default.",
            )
    }
}
