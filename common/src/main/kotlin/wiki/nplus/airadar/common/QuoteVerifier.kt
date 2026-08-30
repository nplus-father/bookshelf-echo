package wiki.nplus.airadar.common

object QuoteVerifier {
    private const val MIN_QUOTE_CHARS = 8

    private val ELLIPSIS = Regex("…+|\\.{3,}")

    private val ATTRIBUTION = Regex("^\\s*(——|—|--)")

    data class Result(val ok: Boolean, val unverified: List<String>)

    fun verify(essayMd: String, sources: List<String>): Result {
        val corpus = normalize(sources.joinToString("\n"))
        val unverified = blockquotes(essayMd)
            .flatMap { it.split(ELLIPSIS) }
            .map(::normalize)
            .filter { it.length >= MIN_QUOTE_CHARS }
            .distinct()
            .filterNot { corpus.contains(it) }
        return Result(unverified.isEmpty(), unverified)
    }

    fun blockquotes(essayMd: String): List<String> {
        val blocks = mutableListOf<MutableList<String>>()
        var open = false
        essayMd.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                val content = trimmed.removePrefix(">").trim()
                if (ATTRIBUTION.containsMatchIn(content)) return@forEach
                if (!open) blocks.add(mutableListOf())
                blocks.last().add(content)
                open = true
            } else if (trimmed.isNotEmpty()) {
                open = false
            }
        }
        return blocks.map { it.joinToString("") }
    }

    fun normalize(s: String): String = buildString(s.length) {
        s.forEach { c -> if (c.isLetterOrDigit()) append(c.lowercaseChar()) }
    }

    fun isAttribution(blockquoteLine: String): Boolean = ATTRIBUTION.containsMatchIn(blockquoteLine)

    fun isCitationLength(normalized: String): Boolean = normalized.length >= MIN_QUOTE_CHARS
}
