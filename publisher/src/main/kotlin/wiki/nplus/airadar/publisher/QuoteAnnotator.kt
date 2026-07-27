package wiki.nplus.airadar.publisher

import wiki.nplus.airadar.common.QuoteVerifier

/**
 * 幫每一段書引文標上它出自哪本書的哪一章。
 *
 * 這是這個站最該有、卻一直沒有的東西：文章裡大段大段的 blockquote 是書櫃的
 * 聲音，但讀者看不出哪一句是誰說的——同一篇常常同時引兩本立場相反的書（07-26
 * 那篇一邊是林區的投資樂園、一邊是柏格的社會悲劇），分不出來的話，整個「新聞
 * × 書」的結構就只剩下標題那一行在講。
 *
 * 判斷方式跟 [QuoteVerifier] 完全相同：正規化後的字串包含。digester 問的是
 * 「這段引文在不在任何來源裡」，這裡問的是「在哪一個來源裡」，共用同一個
 * normalizer 才不會出現「驗得過卻標不出」的引文。
 *
 * 標記寫成純文字的 `<cite>`，書名章節直接寫在裡面：關掉 JS 也讀得到出處，站台
 * 那邊只是把它加上連往章節頁的 href（網址規則只留在前端 library.ts 一份）。
 */
object QuoteAnnotator {

    /** 一個可被引用的來源：某本書的某一章全文。 */
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
            // 作者自己已經寫了出處就不再插一份；太短的引文是術語，不是引用。
            if (!selfAttributed && QuoteVerifier.isCitationLength(quote)) {
                normalized.firstOrNull { (_, text) -> text.contains(quote) }?.let { (source, _) ->
                    // 前後都要空行：markdown 的 HTML 區塊靠空行收尾，少一個的話
                    // 下一段散文會被吃進同一個 HTML block、整段不再被渲染。
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    out.append('\n').append(cite(source)).append("\n\n")
                }
            }
            block.clear()
            selfAttributed = false
        }

        // split 而不是 lineSequence：後者吃掉了「原文結尾有沒有換行」這個資訊，
        // 而這個函式的契約是「沒有可標的引文時，原字串一個位元都不動」。
        val lines = essayMd.split("\n")
        lines.forEachIndexed { i, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                val content = trimmed.removePrefix(">").trim()
                if (QuoteVerifier.isAttribution(content)) selfAttributed = true else block.add(content)
            } else if (trimmed.isNotEmpty()) {
                // 空行不結束一段引文（`>` 之間常常隔著空的 `>` 或空白行），
                // 有實際內容的行才算離開了這個區塊。
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

    /** 書名與章節標題會進 HTML 屬性與內文，一律先跳脫。 */
    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
