package wiki.nplus.airadar.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Canonical message published by every producer, regardless of source.
 * This is the wire contract on the `ingest.x` exchange — additive changes only;
 * breaking changes require a new routing-key version (see ADR-003).
 */
@Serializable
data class ItemEnvelope(
    val source: String,
    val externalId: String,
    val url: String,
    val title: String,
    /** ISO-8601 instant, as reported by the source. */
    val publishedAt: String,
    /** Source-specific payload, kept verbatim for enrichment and audit. */
    val rawPayload: JsonObject? = null,
    val schemaVersion: Int = 1,
)

enum class ItemState {
    RECEIVED,
    ENRICHED,

    /** Passed the resonance gate (ADR-010): the bookshelf has something to say. */
    MATCHED,
    DIGESTED,
    PUBLISHED,
    DUPLICATE,
    FAILED,

    /** Terminal: nearest book too far — no LLM money is ever spent on it. */
    NO_RESONANCE,

    /** Terminal: aged past the freshness cutoff before it reached the daily cap. */
    STALE,
}
