package bosca.communications.mailers.mailgun

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Credentials and sending-domain settings for Mailgun. Values are stored in encrypted platform
 * configuration under [KEY] and resolved for every send so Studio changes do not require a restart.
 */
@Serializable
data class MailgunConfiguration(
    /** Mailgun HTTP API key. */
    val apiKey: String = "",
    /** Verified Mailgun domain used in the Messages API path. */
    val domain: String = "",
    /** Regional Mailgun API origin; use `https://api.eu.mailgun.net` for EU domains. */
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {
    companion object {
        /** Configuration-service key for the Mailgun integration. */
        const val KEY = "mailgun"

        /** Default Mailgun API origin for US-region domains. */
        const val DEFAULT_API_BASE_URL = "https://api.mailgun.net"
    }
}

/** Resolves the current Mailgun configuration from encrypted platform configuration storage. */
internal suspend fun ConfigurationService.getMailgunConfiguration(json: Json): MailgunConfiguration? =
    getValueAs<MailgunConfiguration>(MailgunConfiguration.KEY, json)
