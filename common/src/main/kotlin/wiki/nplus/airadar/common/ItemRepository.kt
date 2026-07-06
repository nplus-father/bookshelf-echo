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

        /** Same (source, external_id) already processed — redelivery or re-poll. */
        data object AlreadySeen : InsertOutcome

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
                    c.commit()
                    return InsertOutcome.AlreadySeen
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
    )

    fun findItem(itemId: Long): ItemRow? = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT i.id, i.source, i.url, i.title, i.state, i.received_at, ic.extracted_text
            FROM items i LEFT JOIN item_contents ic ON ic.item_id = i.id
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
    fun digestsForDay(day: LocalDate): List<DigestedItem> = ds.connection.use { c ->
        c.prepareStatement(
            """
            SELECT i.id, i.source, i.url, i.title, d.summary_zh, d.summary_en, d.tags, d.significance_score, d.category
            FROM digests d JOIN items i ON i.id = d.item_id
            WHERE d.created_at >= ?::timestamptz AND d.created_at < ?::timestamptz
            ORDER BY d.significance_score DESC, i.id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, day.atStartOfDay().atOffset(ZoneOffset.UTC).toString())
            st.setString(2, day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString())
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DigestedItem(
                                itemId = rs.getLong(1),
                                source = rs.getString(2),
                                url = rs.getString(3),
                                title = rs.getString(4),
                                summaryZh = rs.getString(5),
                                summaryEn = rs.getString(6),
                                tagsJson = rs.getString(7),
                                significanceScore = rs.getInt(8),
                                category = rs.getString(9),
                            ),
                        )
                    }
                }
            }
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

    private fun ResultSet.toItemRow() = ItemRow(
        id = getLong(1),
        source = getString(2),
        url = getString(3),
        title = getString(4),
        state = getString(5),
        receivedAt = getObject(6, OffsetDateTime::class.java),
        extractedText = getString(7),
    )
}

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
