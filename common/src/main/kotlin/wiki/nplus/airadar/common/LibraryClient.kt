package wiki.nplus.airadar.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface LibraryClient {
    fun search(query: String, limit: Int = 8): LibrarySearchResult

    fun chapter(chapterId: String, limit: Int = 8000): String?

    companion object {
        fun fromEnv(http: HttpClient): LibraryClient = when (val provider = Config.str("LIBRARY_PROVIDER", "bridge")) {
            "bridge" -> HttpLibraryClient(http)
            "fake" -> FakeLibraryClient()
            else -> error("Unknown LIBRARY_PROVIDER: $provider")
        }
    }
}

data class LibrarySearchResult(
    val booksJson: String,
    val passagesJson: String,
    val topBookDistance: Double?,
)

class HttpLibraryClient(private val http: HttpClient) : LibraryClient {
    private val baseUrl = Config.str("LIBRARY_URL", "http://library-bridge:7788").trimEnd('/')
    private val secret by lazy { Config.str("LIBRARY_SECRET") }

    override fun search(query: String, limit: Int): LibrarySearchResult {
        val body = post(
            "/search",
            kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("limit", kotlinx.serialization.json.JsonPrimitive(limit))
            }.toString(),
        )
        return parseSearch(body)
    }

    override fun chapter(chapterId: String, limit: Int): String? {
        val body = post(
            "/chapter",
            kotlinx.serialization.json.buildJsonObject {
                put("chapter_id", kotlinx.serialization.json.JsonPrimitive(chapterId))
                put("limit", kotlinx.serialization.json.JsonPrimitive(limit))
            }.toString(),
            notFoundIsNull = true,
        ) ?: return null
        return Json.parseToJsonElement(body).jsonObject["content"]?.jsonPrimitive?.content
    }

    private fun post(path: String, body: String, notFoundIsNull: Boolean = false): String? {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("x-bridge-secret", secret)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw RetryableFailure("library-bridge $path failed: ${e.message}", e)
        }
        return when {
            response.statusCode() == 404 && notFoundIsNull -> null
            response.statusCode() == 429 || response.statusCode() >= 500 ->
                throw RetryableFailure("library-bridge $path returned ${response.statusCode()}")
            response.statusCode() != 200 ->
                error("library-bridge $path returned ${response.statusCode()}: ${response.body().take(300)}")
            else -> response.body()
        }
    }

    companion object {
        fun parseSearch(body: String?): LibrarySearchResult {
            val root = Json.parseToJsonElement(body ?: error("empty /search response")).jsonObject
            val books = root["books"]?.jsonArray ?: error("/search response missing books")
            val passages = root["passages"]?.jsonArray ?: error("/search response missing passages")
            val topDistance = books.firstOrNull()?.jsonObject?.get("distance")?.jsonPrimitive?.double
            return LibrarySearchResult(books.toString(), passages.toString(), topDistance)
        }
    }
}

class FakeLibraryClient : LibraryClient {
    override fun search(query: String, limit: Int): LibrarySearchResult = LibrarySearchResult(
        booksJson = """[{"book_id":"fake-book","title_zh":"假書","category":"business","distance":1.03}]""",
        passagesJson = """[{"book_id":"fake-book","book_title":"假書","chapter_id":"fake-book:c1","chapter_title":"假章","snippet":"（測試段落）","distance":1.05,"score":0.0164}]""",
        topBookDistance = 1.03,
    )

    override fun chapter(chapterId: String, limit: Int): String = "（測試章節全文）"
}
