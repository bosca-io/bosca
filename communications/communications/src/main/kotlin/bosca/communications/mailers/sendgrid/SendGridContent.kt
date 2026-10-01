package bosca.communications.mailers.sendgrid

import bosca.communications.mailers.Content
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendGridContent(
    val type: String,
    @SerialName("value")
    override val content: String
) : Content