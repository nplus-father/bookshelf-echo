package wiki.nplus.airadar.digester

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.DigestResult
import wiki.nplus.airadar.common.EssayResult
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.JudgeResult
import wiki.nplus.airadar.common.RetryableFailure
import wiki.nplus.airadar.common.SelectResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One instance per model tier: the defaults are the cheap digest model, the
 * curator constructs a second instance with the SELECT_* config (ADR-009).
 */
class GeminiClient(
    private val http: HttpClient,
    override val model: String = Config.str("GEMINI_MODEL", "gemini-2.5-flash"),
    private val inputUsdPerMTok: Double = Config.double("LLM_INPUT_USD_PER_MTOK", 0.30),
    private val outputUsdPerMTok: Double = Config.double("LLM_OUTPUT_USD_PER_MTOK", 2.50),
) : LlmClient {
    /**
     * 2.5 系列吃低溫吃得很好（0.2 讓 JSON 輸出穩定）；3.x 系列 Google 明講不要
     * 壓低取樣溫度，壓了反而更容易重複、繞圈。所以預設跟著模型世代走，
     * LLM_TEMPERATURE 仍可一次覆寫全部。
     */
    private val temperature: Double =
        Config.double("LLM_TEMPERATURE", if (model.startsWith("gemini-3")) 1.0 else 0.2)
    // Lazy so that construction (and pure parse tests) never require the key.
    private val apiKey by lazy { Config.str("GEMINI_API_KEY") }

    override fun digest(source: String, title: String, url: String, text: String?): DigestResult =
        parseResponse(generate(buildDigestPrompt(source, title, url, text)), model)

    override fun select(candidates: List<ItemRepository.SelectionCandidate>, maxPicks: Int): SelectResult =
        parseSelection(generate(buildSelectPrompt(candidates, maxPicks)), model)

    override fun essay(
        candidate: ItemRepository.EssayCandidate,
        chapters: List<LlmClient.ChapterExcerpt>,
        unverifiedQuotes: List<String>,
    ): EssayResult =
        parseEssay(generate(buildEssayPrompt(candidate, chapters, unverifiedQuotes)), model)

    override fun judge(candidate: ItemRepository.EssayCandidate): JudgeResult =
        parseJudge(generate(buildJudgePrompt(candidate)), model)

    private fun generate(prompt: String): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("temperature", temperature)
            }
        }
        val request = HttpRequest.newBuilder(
            URI.create("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"),
        )
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw RetryableFailure("Gemini request failed: ${e.message}", e)
        }
        when {
            response.statusCode() == 429 || response.statusCode() >= 500 ->
                throw RetryableFailure("Gemini returned ${response.statusCode()}")

            response.statusCode() != 200 ->
                error("Gemini returned ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response.body()
    }

    private fun buildDigestPrompt(source: String, title: String, url: String, text: String?) = """
        You are the senior editor of a daily AI-and-technology intelligence brief read by
        busy engineers and founders. You are given ONE candidate item. Write a tight,
        high-signal digest that says what happened AND why it matters — no filler.

        Respond with ONLY a JSON object (no markdown fences) with exactly these fields:
        {
          "summary_zh": "2-4 sentence digest in Traditional Chinese: the substance AND why it matters. Concrete, factual, self-contained.",
          "summary_en": "2-4 sentence digest in English: the substance AND why it matters. Concrete, factual, self-contained.",
          "tags": ["3-6 short lowercase topical tags"],
          "significance_score": integer 1-5 (5 = major, industry-shifting; 4 = notable across the field; 3 = useful to a sub-field; 2 = niche; 1 = trivial). Be a strict grader — reserve 5 for genuinely major news.,
          "category": one of "research" | "product" | "engineering" | "policy" | "other"
        }

        Guidance:
        - Lead with the substance; do not open with "This article discusses...".
        - For a research paper, state the concrete result/claim and its practical implication.
        - Do not invent details. If content is missing, judge conservatively from the title and lower the score.

        Source: $source
        Title: $title
        URL: $url
        ${text?.let { "Content:\n${it.take(12000)}" } ?: "Content: (not available — judge from the title only, and score conservatively)"}
    """.trimIndent()

    private fun buildSelectPrompt(candidates: List<ItemRepository.SelectionCandidate>, maxPicks: Int): String {
        val list = buildJsonArray {
            candidates.forEach { c ->
                add(
                    buildJsonObject {
                        put("id", c.item.itemId)
                        put("title", c.item.title)
                        put("score", c.item.significanceScore)
                        put("category", c.item.category)
                        put("summary", c.item.summaryEn)
                        // Resonance evidence (ADR-010): how much the bookshelf
                        // has to say. Smaller distance = stronger.
                        c.topBookDistance?.let { put("library_distance", it) }
                        c.booksJson?.let { put("library_books", Json.parseToJsonElement(it)) }
                    },
                )
            }
        }
        return """
            You are the editor-in-chief of a daily commentary column. Each day ONE news item
            is paired with insights from one or two books (organizational theory, history of
            technology, economics, engineering practice, ...) and turned into a deep essay.

            Below are today's ${candidates.size} digested candidates. Choose AT MOST $maxPicks that are
            genuinely worth that treatment. This is a RELATIVE ranking across the whole set:

            - Favor items with lasting significance — a shift in how people build, work, or
              govern — over incremental releases and benchmark bumps.
            - Favor items where a book could plausibly add a frame the news itself lacks.
              Each candidate carries its library retrieval evidence: `library_distance` is
              the raw cosine distance of the nearest book (smaller = the bookshelf resonates
              more) and `library_books` the nearest books. Prefer strong resonance, but a
              conceptually adjacent book beats a keyword-similar one — judge the pairing,
              not just the number.
            - Be strict: picking fewer, or none, is a perfectly good answer.

            Respond with ONLY a JSON object (no markdown fences):
            {
              "picks": [
                {"id": <candidate id>, "reason": "1-2 sentences in Traditional Chinese: why this deserves a book-informed deep commentary."}
              ]
            }

            Candidates:
            $list
        """.trimIndent()
    }

    private fun buildEssayPrompt(
        candidate: ItemRepository.EssayCandidate,
        chapters: List<LlmClient.ChapterExcerpt>,
        unverifiedQuotes: List<String> = emptyList(),
    ): String {
        // 章節在 prompt 裡再截一次的長度。取回來多少（ESSAY_CHAPTER_CHARS）與塞
        // 進 prompt 多少必須是同一個數字，否則 essayist 會引用到它看得見、但
        // 驗證端（拿的是取回的全文）也拿得到、而 publisher 標記時卻切掉的段落。
        val chapterChars = Config.int("ESSAY_CHAPTER_CHARS", 12000)
        val chapterBlocks = chapters.joinToString("\n\n") { ch ->
            "### 《${ch.bookTitle}》｜${ch.chapterTitle}（chapter_id: ${ch.chapterId}）\n${ch.content.take(chapterChars)}"
        }
        // 修訂輪（ADR-012）：確定性的引文比對抓到假引文時，把那幾句原封不動丟回
        // 去，要求只修這裡。整篇重寫會讓好的段落一起賠掉。
        val revision = if (unverifiedQuotes.isEmpty()) {
            ""
        } else {
            """

            ⚠️ 這是修訂稿。你上一稿有 ${unverifiedQuotes.size} 段 blockquote 在上面的原文中逐字比對不到
            （系統用的是去標點、去空白後的字串包含比對，所以不是標點差異的問題，是字句本身對不上）：

            ${unverifiedQuotes.joinToString("\n") { "            - 「${it.take(120)}」" }}

            請重寫：把這幾段換成原文中確實存在的句子（逐字），或改用自己的話轉述、
            移出 blockquote。其餘寫得好的部分請盡量保留，不要整篇重來。
            """.trimIndent()
        }
        return """
            你是一個每日評論專欄的作者。你的獨特之處：用「書櫃」回應時事——從自己讀過的書中
            找出真正能照亮這則新聞的框架，寫一篇有洞見的繁體中文評析。新聞為引、書為體。

            今天的新聞：
            - 標題：${candidate.title}
            - 來源：${candidate.source}（${candidate.url}）
            - 入選理由：${candidate.rationale}
            ${candidate.extractedText?.let { "- 內文（節錄）：\n${it.take(6000)}" } ?: "- 內文：（無全文，僅標題）"}

            書櫃檢索到的候選段落（帶原始距離，越小越相關）：
            ${candidate.passagesJson}

            以下章節已取得全文，引用請以此為準：

            $chapterBlocks

            $revision

            寫作要求：
            - 最多引用「${chapters.size.coerceAtLeast(1)}本」書——就是上面給你全文的那幾本。
            - 引文格式：**所有直接引文一律用 Markdown blockquote（行首 `>`）獨立成段，
              並且必須逐字出自上面的段落或章節全文（新聞內文亦可），一個字都不能改。**
              出處請寫在 blockquote 外面的正文裡。行內的「」只用於強調或指稱，
              不得放直接引文。系統會逐字比對每一段 blockquote；對不上就整篇不發，
              所以拿不準的地方請改用自己的話轉述，不要放進 blockquote。
            - 說清楚書中框架是什麼、它讓我們看見新聞中哪個「非顯而易見」的層面。
            - 不要寫成新聞摘要加書摘：評析的價值在於兩者相撞之後你自己的判斷。
            - 長度約 800-1500 字，Markdown 格式，段落分明；不用列參考書目（系統會加）。
            - 誠實原則：如果這些段落撐不起一篇評析（只是字面巧合、概念不合），
              請放棄——skip=true 並說明原因。寧缺勿濫。

            只回傳 JSON 物件（不要 markdown fence）：
            {
              "skip": false,
              "skip_reason": null,
              "title_zh": "評析標題（繁體中文，不是新聞標題的翻譯）",
              "essay_md": "評析全文（Markdown）",
              "books_used": [
                {"book_id": "…", "book_title": "…", "chapter_id": "…", "chapter_title": "…"}
              ]
            }
            若 skip=true：skip_reason 填原因，其餘欄位可為 null、books_used 為 []。
        """.trimIndent()
    }

    internal fun parseResponse(body: String, model: String): DigestResult {
        val (digest, inputTokens, outputTokens) = extractPayload(body)
        return DigestResult(
            summaryZh = digest.required("summary_zh"),
            summaryEn = digest.required("summary_en"),
            tagsJson = digest["tags"]?.jsonArray?.toString() ?: "[]",
            significanceScore = digest["significance_score"]?.jsonPrimitive?.int?.coerceIn(1, 5)
                ?: error("digest JSON missing significance_score"),
            category = digest.required("category"),
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    internal fun parseSelection(body: String, model: String): SelectResult {
        val (obj, inputTokens, outputTokens) = extractPayload(body)
        val picks = obj["picks"]?.jsonArray
            ?: error("selection JSON missing picks")
        return SelectResult(
            picks = picks.map { p ->
                val pick = p.jsonObject
                SelectResult.Pick(
                    itemId = pick["id"]?.jsonPrimitive?.long ?: error("selection pick missing id"),
                    reason = pick.required("reason"),
                )
            },
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    private fun buildJudgePrompt(candidate: ItemRepository.EssayCandidate): String = """
        你是一個嚴格的把關者。一個系統用向量檢索幫新聞配書，但向量距離常把
        「關鍵詞撞到」誤認為「真有關聯」。你的任務：判斷下面的書櫃證據是否
        真的能為這則新聞提供框架——能讓一篇評析說出非顯而易見的東西。

        新聞：
        - 標題：${candidate.title}
        ${candidate.extractedText?.let { "- 內文（節錄）：\n${it.take(3000)}" } ?: "- 內文：（無全文）"}

        書櫃檢索到的段落：
        ${candidate.passagesJson}

        判斷標準：
        - 真關聯 = 書中的「概念或框架」適用於新聞的「實質內容」，兩者相撞會產生洞見。
        - 假關聯 = 只是主題詞重疊（同講 AI、同講非洲、同有「洪水」二字），
          或新聞本身太瑣碎（產品小更新、宣傳稿、清單型內容）撐不起評析。
        - 有疑慮時判 false。發出一篇硬拗的評析比空手一天糟糕得多。

        只回傳 JSON 物件（不要 markdown fence）：
        {"related": true 或 false, "reason": "一句話說明（繁體中文）"}
    """.trimIndent()

    internal fun parseJudge(body: String, model: String): JudgeResult {
        val (judge, inputTokens, outputTokens) = extractPayload(body)
        return JudgeResult(
            related = judge["related"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: error("judge JSON missing related"),
            reason = judge.required("reason"),
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    internal fun parseEssay(body: String, model: String): EssayResult {
        val (essay, inputTokens, outputTokens) = extractPayload(body)
        val skip = essay["skip"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        if (!skip && essay["essay_md"]?.jsonPrimitive?.content.isNullOrBlank()) {
            error("essay JSON has skip=false but no essay_md")
        }
        return EssayResult(
            skip = skip,
            skipReason = essay["skip_reason"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            titleZh = essay["title_zh"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            essayMd = essay["essay_md"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            booksJson = essay["books_used"]?.jsonArray?.toString() ?: "[]",
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    private data class Payload(val obj: JsonObject, val inputTokens: Int, val outputTokens: Int)

    /**
     * Pull the model's JSON object out of a Gemini envelope. Both failure modes
     * that clog the DLQ — an empty response (SAFETY block / no parts) and a
     * truncated response (MAX_TOKENS → invalid JSON) — are surfaced with the
     * `finishReason`/`blockReason` in the message, so `ops dlq list` shows *why*
     * the item parked instead of an opaque parser offset.
     */
    private fun extractPayload(body: String): Payload {
        val root = Json.parseToJsonElement(body).jsonObject
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.content
        val text = candidate?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: run {
                val block = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
                error("Gemini returned no text${reasonSuffix(finishReason, block)}: ${body.take(300)}")
            }
        val obj = try {
            Json.parseToJsonElement(text.trim()).jsonObject
        } catch (e: Exception) {
            error("Gemini output was not valid JSON${reasonSuffix(finishReason, null)}: ${text.trim().take(300)}")
        }
        val usage = root["usageMetadata"]?.jsonObject
        // thinking tokens 按 output 價計費，卻不算在 candidatesTokenCount 裡。
        // 在 2.5 系列上這一項通常是 0，到了 3.x 它是主要開銷：實測
        // gemini-3.1-pro-preview 回一句話用了 38 個答案 token、335 個 thought
        // token——只記前者，帳本會低估九成，而 DAILY_LLM_BUDGET_USD 這個斷路器
        // 正是靠帳本判斷該不該停。ADR-011 的超支就是漏記帳來的。
        val answerTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.int ?: 0
        val thoughtTokens = usage?.get("thoughtsTokenCount")?.jsonPrimitive?.int ?: 0
        return Payload(
            obj = obj,
            inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.int ?: 0,
            outputTokens = answerTokens + thoughtTokens,
        )
    }

    private fun reasonSuffix(finishReason: String?, blockReason: String?): String =
        listOfNotNull(
            finishReason?.let { "finishReason=$it" },
            blockReason?.let { "blockReason=$it" },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }

    override fun cost(inputTokens: Int, outputTokens: Int): Double =
        inputTokens * inputUsdPerMTok / 1_000_000 + outputTokens * outputUsdPerMTok / 1_000_000

    private fun JsonObject.required(field: String): String =
        this[field]?.jsonPrimitive?.content ?: error("JSON missing $field")
}
