package bosca.communications.mailers.sendgrid

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Credentials for the SendGrid email integration, stored in the platform configuration
 * service under the [KEY] key.
 *
 * The configuration is resolved for each provider operation so changes made in Studio take
 * effect without restarting the server or runner.
 */
@Serializable
data class SendGridConfiguration(
    /** SendGrid API key used to authenticate Mail Send API requests. */
    val apiKey: String = "",
    /** Base64-encoded ECDSA public key used to verify signed Event Webhook requests. */
    val webhookVerificationKey: String = "",
) {
    companion object {
        /** Configuration-service key for the SendGrid integration. */
        const val KEY = "sendgrid"
    }
}

/** Resolves the current SendGrid configuration from encrypted platform configuration storage. */
internal suspend fun ConfigurationService.getSendGridConfiguration(json: Json): SendGridConfiguration? =
    getValueAs<SendGridConfiguration>(SendGridConfiguration.KEY, json)
