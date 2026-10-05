package bosca.feeds.auth

import bosca.feeds.model.FeedAuth
import java.util.Base64

/**
 * Builds outbound request headers from a [FeedAuth] descriptor + its secret. The descriptor
 * comes from the source's FeedConfiguration; the secret comes from `ConfigurationService`. If a kind
 * needs a secret and none is set, no auth header is added.
 */
object FeedAuthHeaders {

    fun forAuth(auth: FeedAuth, secret: String?): Map<String, String> = when (auth) {
        is FeedAuth.None -> emptyMap()

        is FeedAuth.Bearer ->
            secret?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        is FeedAuth.Basic ->
            secret?.let {
                val encoded = Base64.getEncoder().encodeToString("${auth.username}:$it".toByteArray(Charsets.UTF_8))
                mapOf("Authorization" to "Basic $encoded")
            } ?: emptyMap()

        is FeedAuth.ApiKey ->
            secret?.let { mapOf(auth.header to it) } ?: emptyMap()

        is FeedAuth.OAuth2 ->
            throw UnsupportedOperationException(
                "OAuth2 client-credentials token exchange is not implemented yet",
            )
    }
}
