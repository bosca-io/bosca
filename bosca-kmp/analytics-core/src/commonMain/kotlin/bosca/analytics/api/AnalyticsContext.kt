package bosca.analytics.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Context sent alongside each batch of events. */
@Serializable
data class AnalyticsContext(
    @SerialName("app_id")
    val appId: String,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("client_id")
    val clientId: String,
    val device: Device,
    val geo: Geo = Geo(),
    val browser: Browser? = null,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("user_id")
    val userId: String? = null,
)
