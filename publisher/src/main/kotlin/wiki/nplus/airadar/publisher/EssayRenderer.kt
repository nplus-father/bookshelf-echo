package wiki.nplus.airadar.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wiki.nplus.airadar.common.ItemRepository

/**
 * Renders one daily essay (news-echo) into markdown. Provenance — the news the
 * essay answers and the books it draws on — is emitted as structured
 * frontmatter so the site can render it as a first-class header (the triplet:
 * news × book → essay) instead of parsing prose. The body is the LLM's verbatim
 * essay markdown, nothing else.
 *
 * `book_id` from retrieval is the library slug, which is also the book's
 * published Hugo site path (nplus.wiki/<slug>/); the site builds the cover and
 * link URLs from it. chapter_id is "<slug>:<path>", a fallback when book_id is
 * blank so a title-only book still resolves a slug.
 */
object EssayRenderer {

    /**
     * [newsCategory] is the digester's label for the news, [matchBooksJson] the
     * retrieval payload the matcher stored (it carries each book's category and
     * author, which the essayist's own book list does not). Both are optional:
     * an essay rendered without them is still valid, just less groupable — the
     * site treats every one of these fields as optional.
     */
    fun render(
        essay: ItemRepository.EssayRow,
        item: ItemRepository.ItemRow,
        newsSummary: String? = null,
        newsCategory: String? = null,
        matchBooksJson: String? = null,
    ): String {
        val books = Json.parseToJsonElement(essay.booksJson).jsonArray.map { it.jsonObject }
        val meta = bookMetaBySlug(matchBooksJson)
        return buildString {
            appendLine("---")
            appendLine("title: ${yaml(essay.title)}")
            appendLine("date: ${essay.day}")
            appendLine("kind: essay")
            // The model that actually wrote this piece, recorded per essay
            // rather than as a site-wide footnote: the essay tier is a config
            // value and older essays were written by whatever it was then. A
            // reader is owed the specific name, not "AI".
            appendLine("model: ${yaml(essay.model)}")
            appendLine("news:")
            appendLine("  title: ${yaml(item.title)}")
            appendLine("  url: ${yaml(item.url)}")
            appendLine("  source: ${yaml(item.source)}")
            if (!newsSummary.isNullOrBlank()) appendLine("  summary: ${yaml(newsSummary)}")
            // The kind of news this essay answers. Paired with each book's own
            // category below, it is what lets the site say which shelves keep
            // answering which kind of story.
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
                    // "<slug>:<content-path>" — the site deep-links to the chapter's deployed page.
                    if (chapterId != null) appendLine("    chapter_id: ${yaml(chapterId)}")
                    // Category/author come from the retrieval payload, not the
                    // essayist: the shelf's own metadata is the honest source
                    // for how a book should be grouped.
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

    /**
     * The matcher's retrieval payload keyed by library slug. Malformed or absent
     * JSON yields an empty map rather than an exception: this is decoration on
     * top of an essay that is already written and paid for — it must never be
     * the reason a publish fails.
     */
    private fun bookMetaBySlug(matchBooksJson: String?): Map<String, JsonObject> {
        if (matchBooksJson.isNullOrBlank()) return emptyMap()
        val array = runCatching { Json.parseToJsonElement(matchBooksJson).jsonArray }.getOrNull() ?: return emptyMap()
        return array.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val slug = bookSlug(o) ?: return@mapNotNull null
            slug to o
        }.toMap()
    }

    /** book_id (== library slug) if present, else the slug prefix of chapter_id. */
    private fun bookSlug(b: JsonObject): String? {
        b["book_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
        b["chapter_id"]?.jsonPrimitive?.content
            ?.substringBefore(':', "")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return null
    }

    /** A double-quoted YAML scalar; newlines flattened so one summary stays one line. */
    private fun yaml(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""
}
