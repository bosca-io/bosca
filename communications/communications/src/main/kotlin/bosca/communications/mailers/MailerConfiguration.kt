package bosca.communications.mailers

import kotlinx.serialization.Serializable

@Serializable
data class MailerEmail(
    val name: String,
    val email: String
)

@Serializable
data class MailerConfiguration(
    val type: MailerType,
    val from: MailerEmail,
    /**
     * Public page a minted unsubscribe token is appended to (`?token=…`) for BML email
     * renders. Blank/absent disables unsubscribe-link minting.
     */
    val unsubscribeUrl: String? = null,
    /** Public manage-preferences page; same token contract as [unsubscribeUrl]. */
    val preferencesUrl: String? = null,
)