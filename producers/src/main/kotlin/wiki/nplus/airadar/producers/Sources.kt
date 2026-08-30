package wiki.nplus.airadar.producers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wiki.nplus.airadar.common.Config
import wiki.nplus.airadar.common.ItemEnvelope
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private const val UA = "bookshelf-echo/0.1 (personal project; +https://github.com/nplus-father/bookshelf-echo)"

private fun get(http: HttpClient, url: String, vararg headers: String): String {
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).header("User-Agent", UA)
    headers.toList().chunked(2).forEach { (k, v) -> builder.header(k, v) }
    val response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "$url returned ${response.statusCode()}" }
    return response.body()
}

class ArxivSource(private val http: HttpClient) {
    private val categories = Config.str("ARXIV_CATEGORIES", "cs.AI,cs.CL,cs.LG")

    fun poll(): List<ItemEnvelope> {
        val query = categories.split(',').joinToString("+OR+") { "cat:${it.trim()}" }
        val xml = get(http, "https://export.arxiv.org/api/query?search_query=$query&sortBy=submittedDate&sortOrder=descending&max_results=${Config.int("ARXIV_MAX_RESULTS", 30)}")
        return FeedParser.parse(xml).map { item ->
            ItemEnvelope(
                source = "arxiv",
                externalId = item.id.substringAfterLast("/abs/").ifEmpty { item.id },
                url = item.link,
                title = item.title.replace(Regex("\\s+"), " "),
                publishedAt = item.published,
            )
        }
    }
}

class BlogsSource(private val http: HttpClient) {
    private val feeds = Config.str(
        "BLOG_FEEDS",
        "https://openai.com/news/rss.xml,https://huggingface.co/blog/feed.xml,https://simonwillison.net/atom/everything/",
    ).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val maxPerFeed = Config.int("BLOG_MAX_PER_FEED", 20)

    fun poll(): List<ItemEnvelope> = feeds.flatMap { feed ->
        val host = URI.create(feed).host
        runCatching { FeedParser.parse(get(http, feed)) }
            .getOrElse { emptyList() }
            .take(maxPerFeed)
            .map { item ->
                ItemEnvelope(
                    source = "blogs",
                    externalId = "$host:${item.id}",
                    url = item.link,
                    title = item.title,
                    publishedAt = item.published,
                    rawPayload = buildJsonObject { put("feed", feed) },
                )
            }
    }
}

class NewsSource(private val http: HttpClient) {
    private val feeds = Config.str(
        "NEWS_FEEDS",
        "https://feeds.bbci.co.uk/news/rss.xml," +
            "https://feeds.bbci.co.uk/news/business/rss.xml," +
            "https://feeds.bbci.co.uk/news/technology/rss.xml," +
            "https://www.theguardian.com/world/rss," +
            "https://www.theguardian.com/culture/rss",
    ).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val maxPerFeed = Config.int("NEWS_MAX_PER_FEED", 15)

    fun poll(): List<ItemEnvelope> = feeds.flatMap { feed ->
        val host = URI.create(feed).host
        runCatching { FeedParser.parse(get(http, feed)) }
            .getOrElse { emptyList() }
            .take(maxPerFeed)
            .map { item ->
                ItemEnvelope(
                    source = "news",
                    externalId = "$host:${item.id}",
                    url = item.link,
                    title = item.title,
                    publishedAt = item.published,
                    rawPayload = buildJsonObject { put("feed", feed) },
                )
            }
    }
}

class GuardianSource(private val http: HttpClient) {
    private val apiKey = Config.str("GUARDIAN_API_KEY", "")
    private val pageSize = Config.int("GUARDIAN_PAGE_SIZE", 10)
    private val queries = Config.str(
        "GUARDIAN_QUERIES",
        "tag=news/series/the-long-read,section=commentisfree",
    ).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun poll(): List<ItemEnvelope> {
        check(apiKey.isNotBlank()) { "GUARDIAN_API_KEY is not set" }
        return queries.flatMap { filter ->
            runCatching { fetch(filter) }
                .getOrElse { emptyList() }
        }.distinctBy { it.externalId }
    }

    private fun fetch(filter: String): List<ItemEnvelope> {
        val body = get(
            http,
            "https://content.guardianapis.com/search?$filter" +
                "&show-fields=bodyText,trailText&order-by=newest&page-size=$pageSize&api-key=$apiKey",
        )
        val response = Json.parseToJsonElement(body).jsonObject["response"]!!.jsonObject
        return response["results"]!!.jsonArray.map { it.jsonObject }.mapNotNull { article ->
            val fields = article["fields"]?.jsonObject
            val bodyText = fields?.get("bodyText")?.jsonPrimitive?.content
            if (bodyText.isNullOrBlank()) return@mapNotNull null
            ItemEnvelope(
                source = "guardian",
                externalId = article["id"]!!.jsonPrimitive.content,
                url = article["webUrl"]!!.jsonPrimitive.content,
                title = article["webTitle"]!!.jsonPrimitive.content.trim(),
                publishedAt = article["webPublicationDate"]!!.jsonPrimitive.content,
                rawPayload = buildJsonObject {
                    put("filter", filter)
                    put("bodyText", bodyText)
                    fields["trailText"]?.jsonPrimitive?.content?.let { put("trailText", it) }
                },
            )
        }
    }
}

class GhTrendingSource(private val http: HttpClient) {
    fun poll(): List<ItemEnvelope> {
        val since = LocalDate.now().minusDays(Config.long("GH_TRENDING_WINDOW_DAYS", 7))
        val q = URLEncoder.encode("${Config.str("GH_TRENDING_QUERY", "topic:llm")} created:>$since", Charsets.UTF_8)
        val body = get(
            http,
            "https://api.github.com/search/repositories?q=$q&sort=stars&order=desc&per_page=20",
            "Accept", "application/vnd.github+json",
        )
        return Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.map { it.jsonObject }.map { repo ->
            ItemEnvelope(
                source = "gh-trending",
                externalId = repo["full_name"]!!.jsonPrimitive.content,
                url = repo["html_url"]!!.jsonPrimitive.content,
                title = "${repo["full_name"]!!.jsonPrimitive.content} — ${repo["description"]?.jsonPrimitive?.content ?: "no description"}",
                publishedAt = repo["created_at"]?.jsonPrimitive?.content ?: Instant.now().toString(),
                rawPayload = buildJsonObject { put("stars", repo["stargazers_count"]?.jsonPrimitive?.content ?: "0") },
            )
        }
    }
}

class RedditSource(private val http: HttpClient) {
    private val subreddits = Config.str("REDDIT_SUBREDDITS", "MachineLearning+LocalLLaMA")

    fun poll(): List<ItemEnvelope> {
        val body = get(http, "https://www.reddit.com/r/$subreddits/top.json?t=day&limit=${Config.int("REDDIT_LIMIT", 25)}")
        return Json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["children"]!!.jsonArray
            .map { it.jsonObject["data"]!!.jsonObject }
            .map { post ->
                val permalink = "https://www.reddit.com${post["permalink"]!!.jsonPrimitive.content}"
                val external = post["url"]?.jsonPrimitive?.content ?: permalink
                ItemEnvelope(
                    source = "reddit",
                    externalId = post["id"]!!.jsonPrimitive.content,
                    url = if (external.startsWith("https://www.reddit.com")) permalink else external,
                    title = post["title"]!!.jsonPrimitive.content,
                    publishedAt = Instant.ofEpochSecond(post["created_utc"]!!.jsonPrimitive.content.toDouble().toLong()).toString(),
                    rawPayload = subredditPayload(post),
                )
            }
    }

    private fun subredditPayload(post: JsonObject) = buildJsonObject {
        put("subreddit", post["subreddit"]?.jsonPrimitive?.content ?: "")
        put("score", post["score"]?.jsonPrimitive?.content ?: "0")
    }
}
