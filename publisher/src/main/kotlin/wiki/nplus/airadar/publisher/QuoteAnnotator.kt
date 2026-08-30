package wiki.nplus.airadar.publisher

import wiki.nplus.airadar.common.QuoteVerifier

object QuoteAnnotator {

    data class Source(
        val bookTitle: String,
        val chapterTitle: String?,
        val chapterId: String,
        val text: String,
    )

    fun annotate(essayMd: String, sources: List<Source>): String {
        if (sources.isEmpty()) return essayMd
        val normalized = sources.map { it to QuoteVerifier.normalize(it.text) }

        val out = StringBuilder(essayMd.length + 256)
        val block = mutableListOf<String>()
        var selfAttributed = false

        fun flush() {
            if (block.isEmpty()) return
            val quote = QuoteVerifier.normalize(block.joinToString(""))
            if (!selfAttributed && QuoteVerifier.isCitationLength(quote)) {
                normalized.firstOrNull { (_, text) -> text.contains(quote) }?.let { (source, _) ->
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    out.append('\n').append(cite(source)).append("\n\n")
                }
            }
            block.clear()
            selfAttributed = false
        }

        val lines = essayMd.split("\n")
        lines.forEachIndexed { i, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                val content = trimmed.removePrefix(">").trim()
                if (QuoteVerifier.isAttribution(content)) selfAttributed = true else block.add(content)
            } else if (trimmed.isNotEmpty()) {
                flush()
            }
            out.append(line)
            if (i < lines.lastIndex) out.append('\n')
        }
        flush()
        return out.toString()
    }

    private fun cite(source: Source): String {
        val label = buildString {
            append("《").append(source.bookTitle).append("》")
            source.chapterTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        return """<cite class="quote-src" data-chapter-id="${escape(source.chapterId)}">${escape(label)}</cite>"""
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
