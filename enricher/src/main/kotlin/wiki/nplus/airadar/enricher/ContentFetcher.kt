package wiki.nplus.airadar.enricher

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.Settings

class ContentFetcher {
    private val log = LoggerFactory.getLogger(ContentFetcher::class.java)
    private val timeoutMillis = Config.int("FETCH_TIMEOUT_SECONDS", 10) * 1000
    private val maxChars = Settings.fetchMaxChars

    data class Fetched(val level: String, val text: String?)

    /**
     * Best-effort full-text extraction. Failure to fetch is NOT a pipeline
     * failure (paywalls, bot walls, dead links are normal): the item degrades
     * to metadata-only and continues (design doc §4.2).
     */
    fun fetch(url: String): Fetched = try {
        val doc = Jsoup.connect(url)
            .userAgent("bookshelf-echo/0.1 (personal project)")
            .timeout(timeoutMillis)
            .get()
        doc.select("script, style, nav, header, footer, aside").remove()
        val text = (doc.selectFirst("article") ?: doc.body())?.text()?.take(maxChars)
        if (text.isNullOrBlank()) Fetched("METADATA_ONLY", null) else Fetched("FULL", text)
    } catch (e: Exception) {
        log.info("degrading to metadata-only for {}: {}", url, e.toString())
        Fetched("METADATA_ONLY", null)
    }
}
