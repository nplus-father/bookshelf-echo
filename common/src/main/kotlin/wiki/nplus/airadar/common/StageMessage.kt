package wiki.nplus.airadar.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StageMessage(val itemId: Long, val kind: String = "item") {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(body: String): StageMessage = json.decodeFromString(serializer(), body)
    }
}
