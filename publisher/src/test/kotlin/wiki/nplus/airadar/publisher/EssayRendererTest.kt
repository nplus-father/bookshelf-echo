package wiki.nplus.airadar.publisher

import wiki.nplus.airadar.common.ItemRepository.EssayRow
import wiki.nplus.airadar.common.ItemRepository.ItemRow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EssayRendererTest {

    private val at = OffsetDateTime.of(2026, 7, 17, 6, 0, 0, 0, ZoneOffset.UTC)

    private fun item(title: String = "US military to start screening [Hegseth]") = ItemRow(
        id = 1,
        source = "news",
        url = "https://example.com/a?x=1&y=2",
        title = title,
        state = "PUBLISHED",
        receivedAt = at,
        extractedText = null,
        digestedAt = at,
        publishedAt = at,
    )

    private fun essay(booksJson: String, title: String = "Body \"tuning\" as statecraft") = EssayRow(
        day = LocalDate.of(2026, 7, 17),
        itemId = 1,
        title = title,
        essayMd = "The essay body.\n\nSecond paragraph.",
        booksJson = booksJson,
        model = "gemini-2.5-pro",
    )

    @Test
    fun `news and books become frontmatter, body stays pure prose`() {
        val md = EssayRenderer.render(
            essay("""[{"book_id":"goodman-gilman","book_title":"Goodman & Gilman","chapter_id":"goodman-gilman:c1","chapter_title":"Androgens"}]"""),
            item(),
            newsSummary = "The Pentagon will screen troops over 30.",
        )
        // The reader is told which model wrote it, not just "AI".
        assertTrue(md.contains("model: \"gemini-2.5-pro\""))
        assertTrue(md.contains("news:"))
        assertTrue(md.contains("  url: \"https://example.com/a?x=1&y=2\""))
        assertTrue(md.contains("  source: \"news\""))
        assertTrue(md.contains("  summary: \"The Pentagon will screen troops over 30.\""))
        assertTrue(md.contains("  - title: \"Goodman & Gilman\""))
        assertTrue(md.contains("    chapter: \"Androgens\""))
        assertTrue(md.contains("    slug: \"goodman-gilman\""))
        assertTrue(md.contains("    chapter_id: \"goodman-gilman:c1\""))
        // Provenance is frontmatter only — never leaks into the rendered body.
        assertFalse(md.substringAfter("---\n\n").contains("回應新聞"))
        assertFalse(md.substringAfter("---\n\n").contains("本文書目"))
        assertTrue(md.trimEnd().endsWith("Second paragraph."))
    }

    @Test
    fun `quotes in title are escaped for valid yaml`() {
        val md = EssayRenderer.render(essay("[]"), item())
        assertTrue(md.contains("""title: "Body \"tuning\" as statecraft""""))
    }

    @Test
    fun `slug falls back to chapter_id prefix when book_id is blank`() {
        val md = EssayRenderer.render(
            essay("""[{"book_id":"","book_title":"Some Book","chapter_id":"some-book:c3","chapter_title":"Ch"}]"""),
            item(),
        )
        assertTrue(md.contains("    slug: \"some-book\""))
    }

    @Test
    fun `omits summary and books cleanly when absent`() {
        val md = EssayRenderer.render(essay("[]"), item(), newsSummary = null)
        assertFalse(md.contains("summary:"))
        assertFalse(md.contains("books:"))
        assertTrue(md.contains("news:"))
    }

    @Test
    fun `category and author come from the match payload, keyed by slug`() {
        val md = EssayRenderer.render(
            essay("""[{"book_id":"enough","book_title":"夠了","chapter_id":"enough:c1","chapter_title":"成本"}]"""),
            item(),
            newsCategory = "policy",
            matchBooksJson = """[{"book_id":"enough","category":"finance","author":"John C. Bogle"},
                                 {"book_id":"other-book","category":"history"}]""",
        )
        assertTrue(md.contains("  category: \"policy\""))
        assertTrue(md.contains("    category: \"finance\""))
        assertTrue(md.contains("    author: \"John C. Bogle\""))
        // A book in the retrieval payload that the essay did not cite stays out.
        assertFalse(md.contains("history"))
    }

    @Test
    fun `a book quoted from a passage still gets its metadata`() {
        // 引用的書常常只出現在 passages：章節被檢索到了，書本身卻沒擠進書層的
        // top-N（2026-07-26 的《打敗華爾街》就是）。
        val md = EssayRenderer.render(
            essay("""[{"book_id":"beating-the-street","book_title":"打敗華爾街","chapter_id":"beating-the-street:c17","chapter_title":"Ch"}]"""),
            item(),
            matchBooksJson = """[{"book_id":"enough","category":"finance"}]""",
            matchPassagesJson = """[{"book_id":"beating-the-street","category":"finance","author":"Peter Lynch"}]""",
        )
        assertTrue(md.contains("    category: \"finance\""))
        assertTrue(md.contains("    author: \"Peter Lynch\""))
    }

    @Test
    fun `a malformed match payload costs decoration, never the publish`() {
        val md = EssayRenderer.render(
            essay("""[{"book_id":"enough","book_title":"夠了","chapter_id":"enough:c1","chapter_title":"成本"}]"""),
            item(),
            matchBooksJson = "{not json",
        )
        assertTrue(md.contains("    slug: \"enough\""))
        assertFalse(md.contains("    category:"))
    }
}
