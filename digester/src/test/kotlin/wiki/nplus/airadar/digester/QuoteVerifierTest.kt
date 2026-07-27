package wiki.nplus.airadar.digester

import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.QuoteVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuoteVerifierTest {

    private val chapter = """
        組織的溝通結構會決定它所能設計出來的系統結構。這不是一句比喻，而是一個
        可以觀察到的限制：協調成本高到某個程度之後，團隊會停止協調，轉而各自
        在邊界內做局部最佳化。
    """.trimIndent()

    private val news = "OpenAI 宣布重組研究部門，將前訓練與後訓練團隊合併為單一組織。"

    private fun verify(essay: String) = QuoteVerifier.verify(essay, listOf(chapter, news))

    @Test
    fun `verbatim blockquote passes`() {
        val result = verify("前言。\n\n> 組織的溝通結構會決定它所能設計出來的系統結構。\n\n後續評析。")
        assertTrue(result.ok, "unverified: ${result.unverified}")
    }

    @Test
    fun `fabricated blockquote is caught`() {
        val result = verify("> 組織的溝通結構終將被市場的溝通結構取代，這是歷史的必然。")
        assertEquals(false, result.ok)
        assertEquals(1, result.unverified.size)
    }

    @Test
    fun `punctuation and line wrapping do not matter`() {
        // Same words, rewrapped across lines and with the trailing period dropped —
        // still a faithful quote, and the check must not call it fabricated.
        val result = verify("> 組織的溝通結構會決定\n> 它所能設計出來的系統結構\n\n正文。")
        assertTrue(result.ok, "unverified: ${result.unverified}")
    }

    @Test
    fun `attribution line inside the blockquote is not treated as quoted text`() {
        val result = verify("> 組織的溝通結構會決定它所能設計出來的系統結構。\n> ——《人月神話》，第七章\n")
        assertTrue(result.ok, "unverified: ${result.unverified}")
    }

    @Test
    fun `elided quote is checked fragment by fragment`() {
        val ok = verify("> 組織的溝通結構會決定它所能設計出來的系統結構……協調成本高到某個程度之後")
        assertTrue(ok.ok, "unverified: ${ok.unverified}")

        val bad = verify("> 組織的溝通結構會決定它所能設計出來的系統結構……而這正是康威本人最後悔的推論")
        assertEquals(false, bad.ok)
    }

    @Test
    fun `the news article counts as quotable source`() {
        assertTrue(verify("> OpenAI 宣布重組研究部門").ok)
    }

    @Test
    fun `inline corner brackets are emphasis, not quotation`() {
        // The prompt reserves 「」 for emphasis precisely so this cannot fail an
        // otherwise sound essay — checking it would reject the author's own phrasing.
        assertTrue(verify("作者真正想說的是「不讀這本書就看不見的那一層結構限制」。").ok)
    }

    @Test
    fun `short blockquote is below the citation threshold`() {
        assertTrue(verify("> 協調成本").ok)
    }

    @Test
    fun `essay with no quotes at all passes`() {
        assertTrue(verify("純轉述，沒有任何直接引文。").ok)
    }

    @Test
    fun `the fake client's essay clears the gate`() {
        // LLM_PROVIDER=fake exists to run the whole pipeline at zero spend. If
        // its canned essay cannot pass the quote gate, that path silently stops
        // producing essays and the fake stops being a rehearsal of production.
        val chapters = listOf(
            LlmClient.ChapterExcerpt("人月神話", "第七章", "mmm:c7", chapter),
        )
        val candidate = ItemRepository.EssayCandidate(
            itemId = 1,
            source = "guardian",
            url = "https://example.com/1",
            title = "重組研究部門",
            extractedText = news,
            rationale = "測試",
            topBookDistance = 0.9,
            passagesJson = "[]",
        )
        val essay = FakeLlmClient().essay(candidate, chapters)
        val result = QuoteVerifier.verify(essay.essayMd!!, chapters.map { it.content })
        assertTrue(result.ok, "unverified: ${result.unverified}")
    }
}
