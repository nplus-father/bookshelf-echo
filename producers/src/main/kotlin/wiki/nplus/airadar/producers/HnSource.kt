package wiki.nplus.airadar.producers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemEnvelope
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HnSource(private val http: HttpClient) {
    private val query = Config.str("HN_QUERY", "LLM")
    private val minPoints = Config.int("HN_MIN_POINTS", 30)

    fun poll(): List<ItemEnvelope> {
        val url = "https://hn.algolia.com/api/v1/search_by_date" +
            "?tags=story&hitsPerPage=50" +
            "&query=${URLEncoder.encode(query, Charsets.UTF_8)}" +
            "&numericFilters=${URLEncoder.encode("points>$minPoints", Charsets.UTF_8)}"
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "bookshelf-echo/0.1 (personal project)")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HN API returned ${response.statusCode()}" }

        return Json.parseToJsonElement(response.body()).jsonObject["hits"]!!.jsonArray.mapNotNull { hit ->
            val story = hit.jsonObject
            val objectId = story["objectID"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = story["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = story["url"]?.jsonPrimitive?.content
                ?: "https://news.ycombinator.com/item?id=$objectId"
            ItemEnvelope(
                source = "hn",
                externalId = objectId,
                url = url,
                title = title,
                publishedAt = story["created_at"]?.jsonPrimitive?.content ?: "",
                rawPayload = story.filterKeys { it in RAW_KEYS }.let(::JsonObject),
            )
        }
    }

    companion object {
        private val RAW_KEYS = setOf("points", "num_comments", "author", "created_at")
    }
}
