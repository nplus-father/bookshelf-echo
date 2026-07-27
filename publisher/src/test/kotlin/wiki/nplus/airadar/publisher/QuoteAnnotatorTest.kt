package wiki.nplus.airadar.publisher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuoteAnnotatorTest {

    private val lynch = QuoteAnnotator.Source(
        bookTitle = "打敗華爾街",
        chapterTitle = "Uncle Sam's Garage Sale",
        chapterId = "beating-the-street:docs/17/_index.md",
        text = "「民營化」是個奇怪概念：把公眾擁有的東西賣回給公眾，然後它就變私人的了。",
    )
    private val bogle = QuoteAnnotator.Source(
        bookTitle = "夠了",
        chapterTitle = "成本太多，價值太少",
        chapterId = "enough:docs/01/_index.md",
        text = "在金融這一行，成本從來不會憑空消失，它只是換一個名字繼續存在。",
    )

    @Test
    fun `each quote is labelled with the book it came from`() {
        val md = """
            林區這樣寫：

            > 「**民營化**」是個奇怪概念：把公眾擁有的東西賣回給公眾。

            柏格則說：

            > 在金融這一行，成本從來不會憑空消失。
        """.trimIndent()
        val out = QuoteAnnotator.annotate(md, listOf(lynch, bogle))
        assertTrue(out.contains("""data-chapter-id="beating-the-street:docs/17/_index.md""""))
        assertTrue(out.contains("《打敗華爾街》 · Uncle Sam&#039;s Garage Sale") || out.contains("《打敗華爾街》"))
        assertTrue(out.contains("《夠了》 · 成本太多，價值太少"))
        // 兩段引文各自歸屬，不會被同一本書全包。
        assertEquals(1, Regex("beating-the-street").findAll(out).count())
        assertEquals(1, Regex("enough:docs").findAll(out).count())
    }

    @Test
    fun `the cite sits in its own block so the prose after it still renders`() {
        val md = "> 在金融這一行，成本從來不會憑空消失。\n\n這是柏格的算術。\n"
        val out = QuoteAnnotator.annotate(md, listOf(bogle))
        val citeLine = out.lines().indexOfFirst { it.startsWith("<cite") }
        assertTrue(citeLine > 0, "cite 應該獨立成行")
        assertEquals("", out.lines()[citeLine - 1], "cite 之前要有空行")
        assertEquals("", out.lines()[citeLine + 1], "cite 之後要有空行")
    }

    @Test
    fun `a quote the author already attributed is left alone`() {
        val md = """
            > 在金融這一行，成本從來不會憑空消失。
            >
            > ——《夠了》，第一章
        """.trimIndent()
        assertFalse(QuoteAnnotator.annotate(md, listOf(bogle)).contains("<cite"))
    }

    @Test
    fun `a quote from the news, not from a book, gets no book label`() {
        val md = "> 澳洲麥格理集團前執行長風光退休。\n"
        assertEquals(md, QuoteAnnotator.annotate(md, listOf(lynch, bogle)))
    }

    @Test
    fun `no sources means the essay is returned untouched`() {
        val md = "> 在金融這一行，成本從來不會憑空消失。\n"
        assertEquals(md, QuoteAnnotator.annotate(md, emptyList()))
    }
}
