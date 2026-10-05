package bosca.bml.message.client

import kotlinx.serialization.Serializable

/**
 * First-party engagement events: the message server's public tracking routes
 * (`/c/{token}`, `/o/{token}`) publish these over PubSub; communications subscribes and records
 * the CLICKED/OPENED delivery events, and analytics can subscribe to the same channels for
 * click-through-rate reporting.
 */
@Serializable
data class EmailLinkClicked(
    val messageId: String,
    val recipientId: String? = null,
    /** The ORIGINAL destination URL the rewritten link pointed to. */
    val url: String,
    /** Stable identity for this click across every broadcast consumer. */
    val id: String,
) {
    companion object {
        const val CHANNEL: String = "bosca.email.engagement.clicked"
    }
}

@Serializable
data class EmailOpened(
    val messageId: String,
    val recipientId: String? = null,
    /** Stable identity for this open across every broadcast consumer. */
    val id: String,
) {
    companion object {
        const val CHANNEL: String = "bosca.email.engagement.opened"
    }
}
