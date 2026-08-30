package wiki.nplus.airadar.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wiki.nplus.airadar.common.ItemRepository

object EssayRenderer {

    fun render(
        essay: ItemRepository.EssayRow,
        item: ItemRepository.ItemRow,
        newsSummary: String? = null,
        newsCategory: String? = null,
        matchBooksJson: String? = null,
        matchPassagesJson: String? = null,
    ): String {
        val books = Json.parseToJsonElement(essay.booksJson).jsonArray.map { it.jsonObject }
        val meta = bookMetaBySlug(matchBooksJson, matchPassagesJson)
        return buildString {
            appendLine("---")
            appendLine("title: ${yaml(essay.title)}")
            appendLine("date: ${essay.day}")
            appendLine("kind: essay")
            appendLine("model: ${yaml(essay.model)}")
            appendLine("news:")
            appendLine("  title: ${yaml(item.title)}")
            appendLine("  url: ${yaml(item.url)}")
            appendLine("  source: ${yaml(item.source)}")
            if (!newsSummary.isNullOrBlank()) appendLine("  summary: ${yaml(newsSummary)}")
            if (!newsCategory.isNullOrBlank()) appendLine("  category: ${yaml(newsCategory)}")
            if (books.isNotEmpty()) {
                appendLine("books:")
                books.forEach { b ->
                    val title = b["book_title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "?"
                    val chapter = b["chapter_title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val chapterId = b["chapter_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val slug = bookSlug(b)
                    appendLine("  - title: ${yaml(title)}")
                    if (chapter != null) appendLine("    chapter: ${yaml(chapter)}")
                    if (slug != null) appendLine("    slug: ${yaml(slug)}")
                    if (chapterId != null) appendLine("    chapter_id: ${yaml(chapterId)}")
                    val m = slug?.let { meta[it] }
                    m?.get("category")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?.let { appendLine("    category: ${yaml(it)}") }
                    m?.get("author")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?.let { appendLine("    author: ${yaml(it)}") }
                }
            }
            appendLine("---")
            appendLine()
            appendLine(essay.essayMd.trim())
        }
    }

    private fun bookMetaBySlug(vararg payloads: String?): Map<String, JsonObject> {
        val meta = mutableMapOf<String, JsonObject>()
        payloads.forEach { payload ->
            if (payload.isNullOrBlank()) return@forEach
            val array = runCatching { Json.parseToJsonElement(payload).jsonArray }.getOrNull() ?: return@forEach
            array.forEach { element ->
                val o = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                val slug = bookSlug(o) ?: return@forEach
                meta.putIfAbsent(slug, o)
            }
        }
        return meta
    }

    private fun bookSlug(b: JsonObject): String? {
        b["book_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
        b["chapter_id"]?.jsonPrimitive?.content
            ?.substringBefore(':', "")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return null
    }

    private fun yaml(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""
}
