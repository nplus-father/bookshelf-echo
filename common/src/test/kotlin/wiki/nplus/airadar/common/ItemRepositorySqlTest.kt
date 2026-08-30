package wiki.nplus.airadar.common

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ItemRepositorySqlTest {

    private val repo get() = PostgresFixture.repo

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireDocker() {
            assumeTrue(PostgresFixture.available, "Docker unavailable — skipping SQL tests")
        }
    }

    @BeforeEach
    fun clean() = PostgresFixture.reset()

    private var seq = 0

    private fun digestedItem(score: Int = 4, title: String = "news ${seq++}"): Long {
        val outcome = repo.insertReceived(
            ItemEnvelope(
                source = "news",
                externalId = "ext-${seq++}",
                url = "https://example.com/$seq",
                title = title,
                publishedAt = "2026-07-21T00:00:00Z",
            ),
            canonicalUrl = "https://example.com/$seq",
            contentHash = "hash-$seq",
        )
        val id = (outcome as ItemRepository.InsertOutcome.New).itemId
        repo.saveDigest(
            id,
            DigestResult(
                summaryZh = "摘要", summaryEn = "summary", tagsJson = """["t"]""",
                significanceScore = score, category = "other",
                model = "gemini-2.5-flash", inputTokens = 10, outputTokens = 5,
            ),
        )
        return id
    }

    private fun match(itemId: Long, distance: Double = 0.4) =
        repo.saveMatch(itemId, distance, """[{"book_id":"b1"}]""", """[{"chapter_id":"b1:c1"}]""")

    private val longAgo = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `curator only sees items the essayist could actually consume`() {
        val withMatch = digestedItem()
        match(withMatch)
        val withoutMatch = digestedItem()

        val candidates = repo.selectionCandidates(longAgo, minScore = 1)

        assertEquals(listOf(withMatch), candidates.map { it.item.itemId })
        assertTrue(candidates.none { it.item.itemId == withoutMatch })
    }

    @Test
    fun `an already-shortlisted item is not offered to the curator again`() {
        val id = digestedItem()
        match(id)
        assertEquals(1, repo.selectionCandidates(longAgo, minScore = 1).size)

        repo.saveShortlistPick(id, "worth it", "gemini-2.5-pro")

        assertEquals(emptyList(), repo.selectionCandidates(longAgo, minScore = 1).map { it.item.itemId })
    }

    @Test
    fun `minScore and the since cutoff both filter`() {
        val high = digestedItem(score = 5)
        match(high)
        val low = digestedItem(score = 2)
        match(low)

        assertEquals(listOf(high), repo.selectionCandidates(longAgo, minScore = 4).map { it.item.itemId })
        assertEquals(
            emptyList(),
            repo.selectionCandidates(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), minScore = 1)
                .map { it.item.itemId },
        )
    }

    @Test
    fun `a shortlisted item flows to the essayist and leaves once composed`() {
        val id = digestedItem()
        match(id, distance = 0.31)
        repo.saveShortlistPick(id, "resonates", "gemini-2.5-pro")

        val candidates = repo.essayCandidates(ttlDays = 7)
        assertEquals(listOf(id), candidates.map { it.itemId })
        assertEquals("resonates", candidates.single().rationale)
        assertEquals(0.31, candidates.single().topBookDistance)
        assertEquals(1, repo.shortlistPending(ttlDays = 7).size)

        repo.markComposed(id)

        assertEquals(emptyList(), repo.essayCandidates(ttlDays = 7).map { it.itemId })
        assertEquals(emptyList(), repo.shortlistPending(ttlDays = 7).map { it.itemId })
    }

    @Test
    fun `the essayist prefers the closest book`() {
        val far = digestedItem()
        match(far, distance = 0.9)
        repo.saveShortlistPick(far, "far", "m")
        val near = digestedItem()
        match(near, distance = 0.2)
        repo.saveShortlistPick(near, "near", "m")

        assertEquals(listOf(near, far), repo.essayCandidates(ttlDays = 7).map { it.itemId })
    }

    @Test
    fun `every purpose the code books is one the schema accepts`() {
        val id = digestedItem()
        listOf("DIGEST", "WEEKLY_ROLLUP", "SELECT", "ESSAY", "JUDGE").forEach { purpose ->
            repo.recordUsage(id, purpose, "gemini-2.5-flash", 100, 10, 0.001)
        }
        assertEquals(5, repo.llmToday().calls)

        assertFailsWith<Exception> {
            repo.recordUsage(id, "CRITIC", "gemini-2.5-pro", 100, 10, 0.02)
        }
    }

    @Test
    fun `today's spend is itemised by purpose and model, and the parts sum to the total`() {
        val id = digestedItem()
        repo.recordUsage(id, "DIGEST", "gemini-2.5-flash", 1000, 100, 0.0040)
        repo.recordUsage(id, "DIGEST", "gemini-2.5-flash", 2000, 200, 0.0060)
        repo.recordUsage(id, "ESSAY", "gemini-2.5-pro", 5000, 900, 0.1800)
        repo.recordUsage(null, "SELECT", "gemini-2.5-pro", 3000, 300, 0.0300)

        val rows = repo.llmTodayByPurpose()

        assertEquals(listOf("ESSAY", "SELECT", "DIGEST"), rows.map { it.purpose })
        val digest = rows.single { it.purpose == "DIGEST" }
        assertEquals(2, digest.calls)
        assertEquals(3000L, digest.inputTokens)
        assertEquals("gemini-2.5-flash", digest.model)

        val total = repo.llmToday()
        assertEquals(total.calls, rows.sumOf { it.calls })
        assertEquals(total.costUsd, rows.sumOf { it.costUsd }, absoluteTolerance = 1e-9)
        assertEquals(total.inputTokens, rows.sumOf { it.inputTokens })
    }

    @Test
    fun `the same model under two purposes stays two rows`() {
        val id = digestedItem()
        repo.recordUsage(id, "DIGEST", "gemini-2.5-pro", 100, 10, 0.01)
        repo.recordUsage(id, "JUDGE", "gemini-2.5-pro", 100, 10, 0.01)

        assertEquals(2, repo.llmTodayByPurpose().size)
    }

    @Test
    fun `a quiet day reports zero rather than nothing`() {
        assertEquals(0, repo.llmToday().calls)
        assertEquals(0.0, repo.llmToday().costUsd)
        assertEquals(emptyList(), repo.llmTodayByPurpose())
    }
}
