package wiki.nplus.airadar.common

/**
 * Quote fidelity check for the daily essay: every Markdown blockquote in the
 * draft must appear verbatim in the material the essayist was given.
 *
 * This replaces the LLM critic gate. That gate ran AFTER the pro-tier essay was
 * already paid for, so it could only add cost, never avoid it — and a fail
 * bought a SECOND pro-tier call to rewrite the draft. Four of its five rubric
 * items (summary pastiche, non-obviousness, no forced pairing, length) are
 * things the essay prompt already demands and the essayist's own skip clause
 * judges with far more material in hand. The fifth — fabricated quotes — is the
 * one an author model genuinely cannot police in itself, and it is the one that
 * needs no model at all: the chapter text is right here, so the check is a
 * string comparison. Deterministic, free, and it cannot loop.
 *
 * Only blockquotes are checked, and the essay prompt requires book quotes to be
 * written that way for exactly this reason. Inline 「」 is emphasis at least as
 * often as quotation in Chinese prose, so checking it would reject good essays —
 * and a false positive here costs a whole day's column, while a missed quote
 * costs one flawed paragraph. The asymmetry sets the tolerance.
 *
 * Lives in `common` because the publisher runs the same match in reverse: the
 * digester asks "is this quote in any source?", the publisher asks "which
 * source is it in?" so it can label the quote with its book (see
 * publisher's QuoteAnnotator). One normalizer, one blockquote parser — two
 * copies would drift, and a drifted normalizer means a quote that verifies but
 * cannot be attributed.
 */
object QuoteVerifier {
    /** Below this, a "quote" is a term or a fragment, not a citation worth verifying. */
    private const val MIN_QUOTE_CHARS = 8

    private val ELLIPSIS = Regex("…+|\\.{3,}")

    /** `——《書名》，第三章` inside the blockquote is provenance, not quoted text. */
    private val ATTRIBUTION = Regex("^\\s*(——|—|--)")

    data class Result(val ok: Boolean, val unverified: List<String>)

    /**
     * @param sources every text the essayist could legitimately quote — chapter
     *   full text, retrieved passages, the news article itself.
     */
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

    /**
     * Consecutive `>` lines are one quote: where the author broke the lines is
     * not meaningful.
     *
     * Public because the publisher walks the same blocks to attribute them.
     */
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

    /**
     * Keep letters and digits only. Punctuation, spacing and line breaks drift
     * between a book's text and a quotation of it without the quote being any
     * less faithful; a changed character is a different claim.
     */
    fun normalize(s: String): String = buildString(s.length) {
        s.forEach { c -> if (c.isLetterOrDigit()) append(c.lowercaseChar()) }
    }

    /** `——《書名》` 這種出處行：它是引文的標註，不是被引的文字。 */
    fun isAttribution(blockquoteLine: String): Boolean = ATTRIBUTION.containsMatchIn(blockquoteLine)

    /** 這一段引文長到值得當成引用來對待嗎（短於此的是術語，不是引文）。 */
    fun isCitationLength(normalized: String): Boolean = normalized.length >= MIN_QUOTE_CHARS
}
