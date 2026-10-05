package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class NotificationRow(
    val key: String,
    val name: String,
    val description: String,
    val optional: Boolean,
    var emailOptedOut: Boolean,
    var pushOptedOut: Boolean,
)
