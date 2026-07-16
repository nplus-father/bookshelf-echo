package wiki.nplus.airadar.common

import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * All SQL lives here. Idempotency contract (ADR-003): inserts key on
 * (source, external_id); state transitions are conditional on the expected
 * current state so redeliveries become no-ops instead of double side effects.
 */
class ItemRepository(private val ds: DataSource) {

    sealed interface InsertOutcome {
        data class New(val itemId: Long) : InsertOutcome

        /**
         * Same (source, external_id) already inserted — a re-poll, or a
         * redelivery. [state] tells the two apart: anything past RECEIVED was
         * finished by an earlier delivery, but a row still in RECEIVED means a
         * previous attempt committed the insert and then died before the
         * ENRICHED transition, so the work must be resumed rather than dropped.
         */
        data class AlreadySeen(val itemId: Long, val state: String) : InsertOutcome

        /** Same content already ingested via another source. */
        data class DuplicateContent(val itemId: Long, val duplicateOf: Long) : InsertOutcome
    }

    fun insertReceived(envelope: ItemEnvelope, canonicalUrl: String, contentHash: String): InsertOutcome =
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                val id = c.prepareStatement(
                    """
                    INSERT INTO items (source, external_id, url, canonical_url, title, content_hash, published_at, raw_payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::jsonb)
                    ON CONFLICT (source, external_id) DO NOTHING
                    RETURNING id
                    """.trimIndent(),
                ).use { st ->
                    st.setString(1, envelope.source)
                    st.setString(2, envelope.externalId)
                    st.setString(3, envelope.url)
                    st.setString(4, canonicalUrl)
                    st.setString(5, envelope.title)
                    st.setString(6, contentHash)
                    st.setString(7, envelope.publishedAt)
                    st.setString(8, envelope.rawPayload?.toString())
                    st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                } ?: run {
                    // The conflicting row is ours to report on: the caller needs
                    // its state to decide between no-op and resume.
                    val existing = c.prepareStatement(
                        "SELECT id, state FROM items WHERE source = ? AND external_id = ?",
                    ).use { st ->
                        st.setString(1, envelope.source)
                        st.setString(2, envelope.externalId)
                        st.executeQuery().use { rs ->
                            if (rs.next()) InsertOutcome.AlreadySeen(rs.getLong(1), rs.getString(2)) else null
                        }
                    }
                    c.commit()
                    return existing ?: error("insert conflicted on (${envelope.source}, ${envelope.externalId}) but the row is gone")
                }

                val duplicateOf = c.prepareStatement(
                    "SELECT id FROM items WHERE content_hash = ? AND id <> ? AND state <> 'DUPLICATE' ORDER BY id LIMIT 1",
                ).use { st ->
                    st.setString(1, contentHash)
                    st.setLong(2, id)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }

                if (duplicateOf != null) {
                    c.prepareStatement("UPDATE items SET state = 'DUPLICATE', duplicate_of = ?, updated_at = now() WHERE id = ?").use { st ->
                        st.setLong(1, duplicateOf)
                        st.setLong(2, id)
                        st.executeUpdate()
                    }
                    c.commit()
                    InsertOutcome.DuplicateContent(id, duplicateOf)
                } else {
                    c.commit()
                    InsertOutcome.New(id)
                }
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    /** Returns false when the row was not in [from] — i.e. a redelivered message. */
    fun transition(itemId: Long, from: ItemState, to: ItemState): Boolean =
        ds.connection.use { c ->
            c.prepareStatement("UPDATE items SET state = ?, updated_at = now() WHERE id = ? AND state = ?").use { st ->
                st.setString(1, to.name)
                st.setLong(2, itemId)
                st.setString(3, from.name)
                st.executeUpdate() == 1
            }
        }

    fun saveContent(itemId: Long, level: String, text: String?) {
        ds.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO item_contents (item_id, content_level, extracted_text)
                VALUES (?, ?, ?)
                ON CONFLICT (item_id) DO NOTHING
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, itemId)
                st.setString(2, level)
                st.setString(3, text)
                st.executeUpdate()
            }
        }
    }

    data class ItemRow(
        val id: Long,
        val source: String,
        val url: String,
        val title: String,
        val state: String,
        val receivedAt: OffsetDateTime,
        val extractedText: String?,
        /** When the digest was produced; null until the item reaches DIGESTED. */
        val digestedAt: OffsetDateTime?,
        /** Source-reported publication time; null/unparsable for some feeds. */
        val publishedAt: OffsetDateTime?,
    )

    fun findItem(itemId: Long): ItemRow? = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT i.id, i.source, i.url, i.title, i.state, i.received_at, ic.extracted_text, d.created_at, i.published_at
            FROM items i
            LEFT JOIN item_contents ic ON ic.item_id = i.id
            LEFT JOIN digests d ON d.item_id = i.id
            WHERE i.id = ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, itemId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toItemRow() else null }
        }
    }

    fun saveDigest(itemId: Long, d: DigestResult) {
        ds.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO digests (item_id, summary_zh, summary_en, tags, significance_score, category, model)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (item_id) DO NOTHING
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, itemId)
                st.setString(2, d.summaryZh)
                st.setString(3, d.summaryEn)
                st.setString(4, d.tagsJson)
                st.setInt(5, d.significanceScore)
                st.setString(6, d.category)
                st.setString(7, d.model)
                st.executeUpdate()
            }
        }
    }

    fun recordUsage(itemId: Long?, purpose: String, model: String, inputTokens: Int, outputTokens: Int, costUsd: Double) {
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO llm_usage (item_id, purpose, model, input_tokens, output_tokens, cost_usd) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { st ->
                if (itemId != null) st.setLong(1, itemId) else st.setNull(1, java.sql.Types.BIGINT)
                st.setString(2, purpose)
                st.setString(3, model)
                st.setInt(4, inputTokens)
                st.setInt(5, outputTokens)
                st.setDouble(6, costUsd)
                st.executeUpdate()
            }
        }
    }

    fun costSpentToday(): Double = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT COALESCE(SUM(cost_usd), 0) FROM llm_usage WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'utc') AT TIME ZONE 'utc'",
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.next()
                rs.getDouble(1)
            }
        }
    }

    /** Number of items digested so far in the current UTC day — drives the daily digest cap. */
    fun digestCountToday(): Int = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT COUNT(*) FROM llm_usage WHERE purpose = 'DIGEST' AND created_at >= date_trunc('day', now() AT TIME ZONE 'utc') AT TIME ZONE 'utc'",
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    data class DigestedItem(
        val itemId: Long,
        val source: String,
        val url: String,
        val title: String,
        val summaryZh: String,
        val summaryEn: String,
        val tagsJson: String,
        val significanceScore: Int,
        val category: String,
    )

    /** Everything digested on the given UTC day, for idempotent page regeneration. */
    fun digestsForDay(day: LocalDate): List<DigestedItem> = digestsForRange(day, day.plusDays(1))

    /** Digests in [fromInclusive, toExclusive), UTC days. */
    fun digestsForRange(fromInclusive: LocalDate, toExclusive: LocalDate): List<DigestedItem> = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT i.id, i.source, i.url, i.title, d.summary_zh, d.summary_en, d.tags, d.significance_score, d.category
            FROM digests d JOIN items i ON i.id = d.item_id
            WHERE d.created_at >= ?::timestamptz AND d.created_at < ?::timestamptz
            ORDER BY d.significance_score DESC, i.id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, fromInclusive.atStartOfDay().atOffset(ZoneOffset.UTC).toString())
            st.setString(2, toExclusive.atStartOfDay().atOffset(ZoneOffset.UTC).toString())
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDigestedItem()) } }
        }
    }

    /**
     * Any one item digested on the given UTC day, or null if that day has no
     * digests. Enough to rebuild the day's page: the publisher keys the page on
     * the digest's created_at and regenerates it from the whole day, so which
     * item triggers the rebuild does not matter.
     */
    fun anyItemDigestedOn(day: LocalDate): Long? = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT item_id FROM digests WHERE created_at >= ?::timestamptz AND created_at < ?::timestamptz ORDER BY item_id LIMIT 1",
        ).use { st ->
            st.setString(1, day.atStartOfDay().atOffset(ZoneOffset.UTC).toString())
            st.setString(2, day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString())
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    /** Ids of every item sitting in [state], oldest first. Drives `ops redrive`. */
    fun itemIdsInState(state: ItemState): List<Long> = ds.connection.use { c ->
        c.prepareStatement("SELECT id FROM items WHERE state = ? ORDER BY id").use { st ->
            st.setString(1, state.name)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
    }

    fun stateCounts(): Map<String, Int> = ds.connection.use { c ->
        c.prepareStatement("SELECT state, count(*) FROM items GROUP BY state").use { st ->
            st.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) }
            }
        }
    }

    data class LlmToday(val costUsd: Double, val inputTokens: Long, val outputTokens: Long, val calls: Int)

    fun llmToday(): LlmToday = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT COALESCE(SUM(cost_usd), 0), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0), COUNT(*)
            FROM llm_usage
            WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'utc') AT TIME ZONE 'utc'
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.next()
                LlmToday(rs.getDouble(1), rs.getLong(2), rs.getLong(3), rs.getInt(4))
            }
        }
    }

    fun receivedLast24h(): Int = ds.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM items WHERE received_at >= now() - interval '24 hours'").use { st ->
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    fun saveMatch(itemId: Long, topBookDistance: Double, booksJson: String, passagesJson: String) {
        ds.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO matches (item_id, top_book_distance, books, passages)
                VALUES (?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (item_id) DO NOTHING
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, itemId)
                st.setDouble(2, topBookDistance)
                st.setString(3, booksJson)
                st.setString(4, passagesJson)
                st.executeUpdate()
            }
        }
    }

    data class MatchRow(val topBookDistance: Double, val booksJson: String, val passagesJson: String)

    fun matchFor(itemId: Long): MatchRow? = ds.connection.use { c ->
        c.prepareStatement("SELECT top_book_distance, books, passages FROM matches WHERE item_id = ?").use { st ->
            st.setLong(1, itemId)
            st.executeQuery().use { rs ->
                if (rs.next()) MatchRow(rs.getDouble(1), rs.getString(2), rs.getString(3)) else null
            }
        }
    }

    fun essayExistsForDay(day: LocalDate): Boolean = ds.connection.use { c ->
        c.prepareStatement("SELECT 1 FROM essays WHERE day = ?::date").use { st ->
            st.setString(1, day.toString())
            st.executeQuery().use { it.next() }
        }
    }

    fun saveEssay(day: LocalDate, itemId: Long, title: String, essayMd: String, booksJson: String, model: String) {
        ds.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO essays (day, item_id, title, essay_md, books, model)
                VALUES (?::date, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (day) DO NOTHING
                """.trimIndent(),
            ).use { st ->
                st.setString(1, day.toString())
                st.setLong(2, itemId)
                st.setString(3, title)
                st.setString(4, essayMd)
                st.setString(5, booksJson)
                st.setString(6, model)
                st.executeUpdate()
            }
        }
    }

    data class EssayRow(
        val day: LocalDate,
        val itemId: Long,
        val title: String,
        val essayMd: String,
        val booksJson: String,
        val model: String,
    )

    /** The most recent essay for this item — how the publisher resolves an "essay" message. */
    fun essayByItem(itemId: Long): EssayRow? = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT day, item_id, title, essay_md, books, model FROM essays WHERE item_id = ? ORDER BY day DESC LIMIT 1",
        ).use { st ->
            st.setLong(1, itemId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    EssayRow(
                        day = LocalDate.parse(rs.getString(1)),
                        itemId = rs.getLong(2),
                        title = rs.getString(3),
                        essayMd = rs.getString(4),
                        booksJson = rs.getString(5),
                        model = rs.getString(6),
                    )
                } else {
                    null
                }
            }
        }
    }

    data class EssayCandidate(
        val itemId: Long,
        val source: String,
        val url: String,
        val title: String,
        val extractedText: String?,
        val rationale: String,
        val topBookDistance: Double,
        val passagesJson: String,
    )

    /**
     * The essayist's menu: uncomposed shortlist picks within TTL that have
     * match evidence, strongest resonance first, freshest as tie-break.
     */
    fun essayCandidates(ttlDays: Int): List<EssayCandidate> = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT i.id, i.source, i.url, i.title, ic.extracted_text, s.rationale, m.top_book_distance, m.passages
            FROM shortlist s
            JOIN items i ON i.id = s.item_id
            JOIN matches m ON m.item_id = s.item_id
            LEFT JOIN item_contents ic ON ic.item_id = s.item_id
            WHERE s.composed_at IS NULL AND s.shortlisted_at > now() - make_interval(days => ?)
            ORDER BY m.top_book_distance ASC, s.shortlisted_at DESC
            """.trimIndent(),
        ).use { st ->
            st.setInt(1, ttlDays)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            EssayCandidate(
                                itemId = rs.getLong(1),
                                source = rs.getString(2),
                                url = rs.getString(3),
                                title = rs.getString(4),
                                extractedText = rs.getString(5),
                                rationale = rs.getString(6),
                                topBookDistance = rs.getDouble(7),
                                passagesJson = rs.getString(8),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun markComposed(itemId: Long) {
        ds.connection.use { c ->
            c.prepareStatement("UPDATE shortlist SET composed_at = now() WHERE item_id = ? AND composed_at IS NULL").use { st ->
                st.setLong(1, itemId)
                st.executeUpdate()
            }
        }
    }

    /** True once the curator has run for the given UTC day — its idempotency key. */
    fun selectionRunExists(day: LocalDate): Boolean = ds.connection.use { c ->
        c.prepareStatement("SELECT 1 FROM selection_runs WHERE day = ?::date").use { st ->
            st.setString(1, day.toString())
            st.executeQuery().use { it.next() }
        }
    }

    /** Low-water mark for the candidate window; null before the first run ever. */
    fun lastSelectionRunAt(): OffsetDateTime? = ds.connection.use { c ->
        c.prepareStatement("SELECT MAX(created_at) FROM selection_runs").use { st ->
            st.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1, OffsetDateTime::class.java)
            }
        }
    }

    fun recordSelectionRun(day: LocalDate, model: String, candidateCount: Int, pickedCount: Int) {
        ds.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO selection_runs (day, model, candidate_count, picked_count)
                VALUES (?::date, ?, ?, ?)
                ON CONFLICT (day) DO NOTHING
                """.trimIndent(),
            ).use { st ->
                st.setString(1, day.toString())
                st.setString(2, model)
                st.setInt(3, candidateCount)
                st.setInt(4, pickedCount)
                st.executeUpdate()
            }
        }
    }

    data class SelectionCandidate(
        val item: DigestedItem,
        /** Resonance signal from the matcher (ADR-010); null for pre-gate items. */
        val topBookDistance: Double?,
        val booksJson: String?,
    )

    /**
     * Digests produced after [since] that scored at least [minScore] and are
     * not yet shortlisted — the curator's candidate set, with each item's
     * resonance evidence attached. Ordered like the daily page so the LLM sees
     * the strongest items first.
     */
    fun selectionCandidates(since: OffsetDateTime, minScore: Int): List<SelectionCandidate> = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT i.id, i.source, i.url, i.title, d.summary_zh, d.summary_en, d.tags, d.significance_score, d.category,
                   m.top_book_distance, m.books
            FROM digests d
            JOIN items i ON i.id = d.item_id
            LEFT JOIN shortlist s ON s.item_id = d.item_id
            LEFT JOIN matches m ON m.item_id = d.item_id
            WHERE d.created_at > ? AND d.significance_score >= ? AND s.item_id IS NULL
            ORDER BY d.significance_score DESC, i.id
            """.trimIndent(),
        ).use { st ->
            st.setObject(1, since)
            st.setInt(2, minScore)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val distance = rs.getDouble(10).let { if (rs.wasNull()) null else it }
                        add(SelectionCandidate(rs.toDigestedItem(), distance, rs.getString(11)))
                    }
                }
            }
        }
    }

    fun saveShortlistPick(itemId: Long, rationale: String, model: String) {
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO shortlist (item_id, rationale, model) VALUES (?, ?, ?) ON CONFLICT (item_id) DO NOTHING",
            ).use { st ->
                st.setLong(1, itemId)
                st.setString(2, rationale)
                st.setString(3, model)
                st.executeUpdate()
            }
        }
    }

    data class ShortlistRow(
        val itemId: Long,
        val title: String,
        val url: String,
        val rationale: String,
        val shortlistedAt: OffsetDateTime,
    )

    /** Picks not yet consumed by a composition and younger than [ttlDays] — the live pool. */
    fun shortlistPending(ttlDays: Int): List<ShortlistRow> = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT s.item_id, i.title, i.url, s.rationale, s.shortlisted_at
            FROM shortlist s JOIN items i ON i.id = s.item_id
            WHERE s.composed_at IS NULL AND s.shortlisted_at > now() - make_interval(days => ?)
            ORDER BY s.shortlisted_at DESC
            """.trimIndent(),
        ).use { st ->
            st.setInt(1, ttlDays)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ShortlistRow(
                                itemId = rs.getLong(1),
                                title = rs.getString(2),
                                url = rs.getString(3),
                                rationale = rs.getString(4),
                                shortlistedAt = rs.getObject(5, OffsetDateTime::class.java),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun saveSnapshot(snapshotJson: String) {
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO metrics_snapshots (snapshot) VALUES (?::jsonb)").use { st ->
                st.setString(1, snapshotJson)
                st.executeUpdate()
            }
        }
    }

    fun recordPublish(kind: String, targetPath: String, gitCommit: String?, itemCount: Int, status: String) {
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO publish_log (kind, target_path, git_commit, item_count, status) VALUES (?, ?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, kind)
                st.setString(2, targetPath)
                st.setString(3, gitCommit)
                st.setInt(4, itemCount)
                st.setString(5, status)
                st.executeUpdate()
            }
        }
    }

    private fun ResultSet.toDigestedItem() = DigestedItem(
        itemId = getLong(1),
        source = getString(2),
        url = getString(3),
        title = getString(4),
        summaryZh = getString(5),
        summaryEn = getString(6),
        tagsJson = getString(7),
        significanceScore = getInt(8),
        category = getString(9),
    )

    private fun ResultSet.toItemRow() = ItemRow(
        id = getLong(1),
        source = getString(2),
        url = getString(3),
        title = getString(4),
        state = getString(5),
        receivedAt = getObject(6, OffsetDateTime::class.java),
        extractedText = getString(7),
        digestedAt = getObject(8, OffsetDateTime::class.java),
        publishedAt = getObject(9, OffsetDateTime::class.java),
    )
}

/** Structured output of the LLM selection step (curator), provider-agnostic. */
data class SelectResult(
    val picks: List<Pick>,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
) {
    data class Pick(val itemId: Long, val reason: String)
}

/** Structured output of the relevance judge, provider-agnostic. */
data class JudgeResult(
    val related: Boolean,
    val reason: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** Structured output of the LLM essay step (essayist), provider-agnostic. */
data class EssayResult(
    /** The model may decline: passages that cannot support an essay produce no essay (寧缺勿濫). */
    val skip: Boolean,
    val skipReason: String?,
    val titleZh: String?,
    val essayMd: String?,
    val booksJson: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** Structured output of the LLM digest step, provider-agnostic. */
data class DigestResult(
    val summaryZh: String,
    val summaryEn: String,
    val tagsJson: String,
    val significanceScore: Int,
    val category: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)
