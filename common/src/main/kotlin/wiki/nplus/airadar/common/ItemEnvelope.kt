package wiki.nplus.airadar.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ItemEnvelope(
    val source: String,
    val externalId: String,
    val url: String,
    val title: String,
    val publishedAt: String,
    val rawPayload: JsonObject? = null,
    val schemaVersion: Int = 1,
)

enum class ItemState {
    RECEIVED,
    ENRICHED,

    MATCHED,
    DIGESTED,
    PUBLISHED,
    DUPLICATE,
    FAILED,

    NO_RESONANCE,

    STALE,
}
