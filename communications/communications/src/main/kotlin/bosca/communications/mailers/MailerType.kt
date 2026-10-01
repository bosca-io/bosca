package bosca.communications.mailers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MailerType {
    @SerialName("sendgrid")
    SENDGRID,

    @SerialName("mailgun")
    MAILGUN,
}
