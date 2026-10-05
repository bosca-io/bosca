package bosca.communications.mailers.sendgrid

import bosca.communications.mailers.Email
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SendGridEmail(
    override val name: String,
    override val email: String,
    @Transient
    override val customArguments: Map<String, String> = emptyMap(),
) : Email
