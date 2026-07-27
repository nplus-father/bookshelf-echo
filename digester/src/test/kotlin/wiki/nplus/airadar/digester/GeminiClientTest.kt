package wiki.nplus.airadar.digester

import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeminiClientTest {

    // parseResponse/cost are pure; the API key is resolved lazily and never
    // touched by these tests.
    private fun client(): GeminiClient = GeminiClient(HttpClient.newHttpClient())

    private val sampleResponse = """
        {
          "candidates": [{
            "content": {"parts": [{"text": "{\"summary_zh\": \"中文摘要\", \"summary_en\": \"English summary\", \"tags\": [\"llm\", \"release\"], \"significance_score\": 4, \"category\": \"product\"}"}]}
          }],
          "usageMetadata": {"promptTokenCount": 1200, "candidatesTokenCount": 150}
        }
    """.trimIndent()

    @Test
    fun `parses a well-formed response`() {
        val result = client().parseResponse(sampleResponse, "gemini-test")
        assertEquals("中文摘要", result.summaryZh)
        assertEquals("English summary", result.summaryEn)
        assertEquals("""["llm","release"]""", result.tagsJson)
        assertEquals(4, result.significanceScore)
        assertEquals("product", result.category)
        assertEquals(1200, result.inputTokens)
        assertEquals(150, result.outputTokens)
    }

    @Test
    fun `thinking tokens count as output — they are billed as output`() {
        // 3.x 系列的主要開銷在 thoughtsTokenCount，而它不在 candidatesTokenCount
        // 裡。少加這一項，帳本會低估到讓每日預算的斷路器形同虛設。
        val body = sampleResponse.replace(
            """"candidatesTokenCount": 150""",
            """"candidatesTokenCount": 150, "thoughtsTokenCount": 900""",
        )
        val result = client().parseResponse(body, "gemini-test")
        assertEquals(1050, result.outputTokens)
    }

    @Test
    fun `score outside 1-5 is clamped`() {
        val body = sampleResponse.replace("\\\"significance_score\\\": 4", "\\\"significance_score\\\": 9")
        assertEquals(5, client().parseResponse(body, "gemini-test").significanceScore)
    }

    @Test
    fun `missing required field fails fast (goes to DLQ, not retry)`() {
        val body = sampleResponse.replace("summary_zh", "summary_xx")
        assertFailsWith<IllegalStateException> { client().parseResponse(body, "gemini-test") }
    }

    @Test
    fun `a safety-blocked response names the reason (diagnosable DLQ)`() {
        val body = """
            {
              "candidates": [{"finishReason": "SAFETY", "content": {"parts": []}}],
              "promptFeedback": {"blockReason": "SAFETY"}
            }
        """.trimIndent()
        val e = assertFailsWith<IllegalStateException> { client().parseResponse(body, "gemini-test") }
        assertContains(e.message ?: "", "SAFETY")
    }

    @Test
    fun `a truncated response names MAX_TOKENS instead of an opaque parser error`() {
        // finishReason=MAX_TOKENS with the inner JSON cut off mid-object.
        val body = """
            {
              "candidates": [{"finishReason": "MAX_TOKENS", "content": {"parts": [{"text": "{\"summary_zh\": \"半句就被截"}]}}],
              "usageMetadata": {"promptTokenCount": 1200, "candidatesTokenCount": 8192}
            }
        """.trimIndent()
        val e = assertFailsWith<IllegalStateException> { client().parseResponse(body, "gemini-test") }
        assertContains(e.message ?: "", "MAX_TOKENS")
    }

    @Test
    fun `cost uses per-mtok rates`() {
        val cost = client().cost(1_000_000, 1_000_000)
        assertEquals(0.30 + 2.50, cost, 1e-9)
    }

    private val sampleSelection = """
        {
          "candidates": [{
            "content": {"parts": [{"text": "{\"picks\": [{\"id\": 42, \"reason\": \"值得深評\"}, {\"id\": 7, \"reason\": \"框架契合\"}]}"}]}
          }],
          "usageMetadata": {"promptTokenCount": 3000, "candidatesTokenCount": 200}
        }
    """.trimIndent()

    @Test
    fun `parses a well-formed selection`() {
        val result = client().parseSelection(sampleSelection, "gemini-test")
        assertEquals(listOf(42L, 7L), result.picks.map { it.itemId })
        assertEquals("值得深評", result.picks[0].reason)
        assertEquals(3000, result.inputTokens)
        assertEquals(200, result.outputTokens)
    }

    @Test
    fun `empty picks is a valid selection`() {
        val body = sampleSelection.replace(
            "[{\\\"id\\\": 42, \\\"reason\\\": \\\"值得深評\\\"}, {\\\"id\\\": 7, \\\"reason\\\": \\\"框架契合\\\"}]",
            "[]",
        )
        assertEquals(0, client().parseSelection(body, "gemini-test").picks.size)
    }

    @Test
    fun `selection missing picks fails fast`() {
        val body = sampleSelection.replace("picks", "choices")
        assertFailsWith<IllegalStateException> { client().parseSelection(body, "gemini-test") }
    }

    private fun essayResponse(payload: String) = """
        {
          "candidates": [{"content": {"parts": [{"text": ${kotlinx.serialization.json.JsonPrimitive(payload)}}]}}],
          "usageMetadata": {"promptTokenCount": 8000, "candidatesTokenCount": 1200}
        }
    """.trimIndent()

    @Test
    fun `parses a composed essay`() {
        val payload = """{"skip": false, "skip_reason": null, "title_zh": "評析標題", "essay_md": "# 內文", "books_used": [{"book_id": "b1", "book_title": "書一", "chapter_id": "b1:c1", "chapter_title": "章一"}]}"""
        val result = client().parseEssay(essayResponse(payload), "gemini-test")
        assertEquals(false, result.skip)
        assertEquals("評析標題", result.titleZh)
        assertEquals("# 內文", result.essayMd)
        assertEquals(8000, result.inputTokens)
        assertEquals(1200, result.outputTokens)
    }

    @Test
    fun `parses a declined essay`() {
        val payload = """{"skip": true, "skip_reason": "字面巧合", "title_zh": null, "essay_md": null, "books_used": []}"""
        val result = client().parseEssay(essayResponse(payload), "gemini-test")
        assertEquals(true, result.skip)
        assertEquals("字面巧合", result.skipReason)
        assertEquals(null, result.essayMd)
    }

    @Test
    fun `parses a judge verdict`() {
        val body = essayResponse("""{"related": false, "reason": "主題詞重疊而已"}""")
        val result = client().parseJudge(body, "gemini-test")
        assertEquals(false, result.related)
        assertEquals("主題詞重疊而已", result.reason)
    }

    @Test
    fun `judge without verdict fails fast`() {
        val body = essayResponse("""{"reason": "沒有結論"}""")
        assertFailsWith<IllegalStateException> { client().parseJudge(body, "gemini-test") }
    }

    @Test
    fun `essay claiming success without body fails fast`() {
        val payload = """{"skip": false, "skip_reason": null, "title_zh": "t", "essay_md": "", "books_used": []}"""
        assertFailsWith<IllegalStateException> { client().parseEssay(essayResponse(payload), "gemini-test") }
    }
}
