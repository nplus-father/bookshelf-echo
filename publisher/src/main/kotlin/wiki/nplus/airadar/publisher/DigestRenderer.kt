package wiki.nplus.airadar.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import wiki.nplus.airadar.common.ItemRepository.DigestedItem
import java.time.LocalDate

object DigestRenderer {

    private const val HEADLINE_MIN_SCORE = 4

    fun renderDaily(day: LocalDate, items: List<DigestedItem>): String {
        val (headlines, alsoSeen) = items.partition { it.significanceScore >= HEADLINE_MIN_SCORE }
        return buildString {
            appendLine("---")
            appendLine("title: \"Bookshelf Echo — $day\"")
            appendLine("date: $day")
            appendLine("itemCount: ${items.size}")
            appendLine("---")
            appendLine()
            if (headlines.isNotEmpty()) {
                appendLine("## Highlights")
                appendLine()
                headlines.forEach { appendLine(renderItem(it)) }
            }
            if (alsoSeen.isNotEmpty()) {
                appendLine("## Also seen")
                appendLine()
                alsoSeen.forEach { appendLine("- [${it.title}](${it.url}) — ${it.summaryEn} `${it.source}`") }
            }
            if (items.isEmpty()) {
                appendLine("No items digested today.")
            }
        }
    }

    fun renderWeekly(weekStart: LocalDate, isoWeekLabel: String, items: List<DigestedItem>): String {
        val highlights = items.filter { it.significanceScore >= HEADLINE_MIN_SCORE }
        return buildString {
            appendLine("---")
            appendLine("title: \"Bookshelf Echo Weekly — $isoWeekLabel\"")
            appendLine("date: $weekStart")
            appendLine("itemCount: ${items.size}")
            appendLine("highlightCount: ${highlights.size}")
            appendLine("---")
            appendLine()
            highlights.groupBy { it.category }.toSortedMap().forEach { (category, group) ->
                appendLine("## ${category.replaceFirstChar { it.uppercase() }}")
                appendLine()
                group.forEach { appendLine(renderItem(it)) }
            }
            if (highlights.isEmpty()) appendLine("No highlights this week (${items.size} items digested).")
        }
    }

    private fun renderItem(item: DigestedItem): String {
        val tags = runCatching {
            Json.parseToJsonElement(item.tagsJson).jsonArray.joinToString(" ") { "`${it.jsonPrimitive.content}`" }
        }.getOrDefault("")
        return buildString {
            appendLine("### [${item.title}](${item.url})")
            appendLine()
            appendLine("- ${item.summaryZh}")
            appendLine("- ${item.summaryEn}")
            appendLine("- score ${item.significanceScore}/5 · ${item.category} · via ${item.source} $tags")
        }
    }
}
