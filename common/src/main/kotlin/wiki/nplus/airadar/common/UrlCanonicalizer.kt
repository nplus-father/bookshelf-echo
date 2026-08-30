package wiki.nplus.airadar.common

import java.net.URI
import java.security.MessageDigest

object UrlCanonicalizer {

    private val TRACKING_PARAM_PREFIXES = listOf("utm_", "fbclid", "gclid", "ref_", "mc_")

    fun canonicalize(rawUrl: String): String {
        val uri = URI(rawUrl.trim())
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return rawUrl.trim()
        val path = uri.path.orEmpty().trimEnd('/')
        val query = uri.query
            ?.split('&')
            ?.filterNot { param -> TRACKING_PARAM_PREFIXES.any { param.startsWith(it) } }
            ?.sorted()
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
        return buildString {
            append(host)
            append(path)
            if (query != null) append('?').append(query)
        }
    }

    fun contentHash(title: String, rawUrl: String): String {
        val normalizedTitle = title.trim().lowercase().replace(Regex("\\s+"), " ")
        val input = "$normalizedTitle|${canonicalize(rawUrl)}"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
