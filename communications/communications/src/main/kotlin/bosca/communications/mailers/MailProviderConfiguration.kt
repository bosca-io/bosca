package bosca.communications.mailers

import kotlinx.serialization.Serializable

/** Provider selection stored in platform configuration; null uses the deployment setting. */
@Serializable
data class MailProviderConfiguration(val type: MailerType? = null) {
    companion object {
        const val KEY = "mailer"
    }
}
