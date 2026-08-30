package wiki.nplus.airadar.digester

import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.DigestResult
import wiki.nplus.airadar.common.EssayResult
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.JudgeResult
import wiki.nplus.airadar.common.SelectResult
import java.net.http.HttpClient

interface LlmClient {
    val model: String

    fun digest(source: String, title: String, url: String, text: String?): DigestResult

    fun select(candidates: List<ItemRepository.SelectionCandidate>, maxPicks: Int): SelectResult

    fun essay(
        candidate: ItemRepository.EssayCandidate,
        chapters: List<ChapterExcerpt>,
        unverifiedQuotes: List<String> = emptyList(),
    ): EssayResult

    fun judge(candidate: ItemRepository.EssayCandidate): JudgeResult

    fun cost(inputTokens: Int, outputTokens: Int): Double = 0.0

    data class ChapterExcerpt(val bookTitle: String, val chapterTitle: String, val chapterId: String, val content: String)

    companion object {
        private fun gemini(http: HttpClient, build: (HttpClient) -> GeminiClient): LlmClient =
            when (val provider = Config.str("LLM_PROVIDER", "gemini")) {
                "gemini" -> build(http)
                "fake" -> FakeLlmClient()
                else -> error("Unknown LLM_PROVIDER: $provider")
            }

        fun fromEnv(http: HttpClient): LlmClient = gemini(http) { GeminiClient(it) }

        fun digesterFromEnv(http: HttpClient): LlmClient = gemini(http) {
            GeminiClient(
                it,
                model = Config.str("DIGEST_MODEL", "gemini-2.5-flash"),
                inputUsdPerMTok = Config.double("DIGEST_INPUT_USD_PER_MTOK", 0.30),
                outputUsdPerMTok = Config.double("DIGEST_OUTPUT_USD_PER_MTOK", 2.50),
            )
        }

        fun selectorFromEnv(http: HttpClient): LlmClient = gemini(http) {
            GeminiClient(
                it,
                model = Config.str("SELECT_MODEL", "gemini-2.5-pro"),
                inputUsdPerMTok = Config.double("SELECT_INPUT_USD_PER_MTOK", 1.25),
                outputUsdPerMTok = Config.double("SELECT_OUTPUT_USD_PER_MTOK", 10.00),
            )
        }

        fun essayistFromEnv(http: HttpClient): LlmClient = gemini(http) {
            GeminiClient(
                it,
                model = Config.str("ESSAY_MODEL", "gemini-2.5-pro"),
                inputUsdPerMTok = Config.double("ESSAY_INPUT_USD_PER_MTOK", 1.25),
                outputUsdPerMTok = Config.double("ESSAY_OUTPUT_USD_PER_MTOK", 10.00),
                timeoutSeconds = Config.int("ESSAY_TIMEOUT_SECONDS", 180),
            )
        }

        fun judgeFromEnv(http: HttpClient): LlmClient = gemini(http) {
            GeminiClient(
                it,
                model = Config.str("JUDGE_MODEL", "gemini-2.5-flash"),
                inputUsdPerMTok = Config.double("JUDGE_INPUT_USD_PER_MTOK", 0.30),
                outputUsdPerMTok = Config.double("JUDGE_OUTPUT_USD_PER_MTOK", 2.50),
            )
        }
    }
}

class UnusableResponse(
    message: String,
    val inputTokens: Int,
    val outputTokens: Int,
) : IllegalStateException(message)

class FakeLlmClient : LlmClient {
    override val model = "fake"

    override fun digest(source: String, title: String, url: String, text: String?): DigestResult = DigestResult(
        summaryZh = "（測試摘要）$title",
        summaryEn = "(fake summary) $title",
        tagsJson = """["fake"]""",
        significanceScore = 3,
        category = "other",
        model = model,
        inputTokens = 0,
        outputTokens = 0,
    )

    override fun select(candidates: List<ItemRepository.SelectionCandidate>, maxPicks: Int): SelectResult = SelectResult(
        picks = candidates.take(maxPicks).map { SelectResult.Pick(it.item.itemId, "（測試理由）${it.item.title}") },
        model = model,
        inputTokens = 0,
        outputTokens = 0,
    )

    override fun judge(candidate: ItemRepository.EssayCandidate): JudgeResult = JudgeResult(
        related = true,
        reason = "（測試判定）",
        model = model,
        inputTokens = 0,
        outputTokens = 0,
    )

    override fun essay(
        candidate: ItemRepository.EssayCandidate,
        chapters: List<LlmClient.ChapterExcerpt>,
        unverifiedQuotes: List<String>,
    ): EssayResult = EssayResult(
        skip = false,
        skipReason = null,
        titleZh = "（測試評析）${candidate.title}",
        essayMd = chapters.joinToString("\n\n") { "> ${it.content.take(60)}" }
            .let { "（測試評析內文）\n\n$it" },
        booksJson = """[{"book_id":"fake-book","book_title":"假書","chapter_id":"fake-book:c1","chapter_title":"假章"}]""",
        model = model,
        inputTokens = 0,
        outputTokens = 0,
    )
}
