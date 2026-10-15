package bosca.communications.mailers

import bosca.communications.mailers.mailgun.MailgunMailer
import bosca.communications.mailers.sendgrid.SendGridMailer
import bosca.communications.mailers.smtp.SmtpMailer
import bosca.configuration.service.ConfigurationService
import kotlinx.serialization.json.Json

/** Resolves one provider for the complete construction and delivery of an email. */
class MailerProvider(
    private val json: Json,
    private val configuration: MailerConfiguration,
    private val configurationService: ConfigurationService,
) {
    private val mailers = mapOf(
        MailerType.SENDGRID to SendGridMailer(json, configurationService),
        MailerType.MAILGUN to MailgunMailer(json, configurationService),
        MailerType.SMTP to SmtpMailer(json, configurationService),
    )

    /** Reads the current stored choice, using the deployment choice when no override is set. */
    suspend fun get(): Mailer {
        val stored = configurationService.getByKey(MailProviderConfiguration.KEY)
            ?.let { configurationService.getValue(it.id) }
            ?.let { json.decodeFromJsonElement(MailProviderConfiguration.serializer(), it) }
        return mailers.getValue(stored?.type ?: configuration.type)
    }
}
