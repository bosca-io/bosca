package bosca.feeds.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **non-secret** outbound-auth descriptor for a feed source, stored as part of
 * [FeedConfiguration] in the content `Source.configuration`. It records *how* to authenticate (the
 * kind + non-secret fields like username / header name / token URL / client id) but never the secret
 * itself. The single secret value (password / bearer token / api-key value / client secret) is held
 * in `ConfigurationService` under a per-source key as a [FeedAuthSecret] — see `FeedSourceService`.
 * A sealed hierarchy serialized polymorphically (discriminator `type`).
 */
@Serializable
sealed interface FeedAuth {

    @Serializable
    @SerialName("none")
    data object None : FeedAuth

    @Serializable
    @SerialName("basic")
    data class Basic(val username: String) : FeedAuth

    @Serializable
    @SerialName("bearer")
    data object Bearer : FeedAuth

    @Serializable
    @SerialName("apiKey")
    data class ApiKey(val header: String) : FeedAuth

    @Serializable
    @SerialName("oauth2")
    data class OAuth2(
        val tokenUrl: String,
        val clientId: String,
        val scope: String? = null,
    ) : FeedAuth
}

/**
 * The single secret material for a source's outbound auth (the password / bearer token / api-key
 * value / OAuth2 client secret, depending on the [FeedAuth] kind). Stored in `ConfigurationService`
 * (private, access-controlled) — never in `Source.configuration`.
 */
@Serializable
data class FeedAuthSecret(val secret: String)
