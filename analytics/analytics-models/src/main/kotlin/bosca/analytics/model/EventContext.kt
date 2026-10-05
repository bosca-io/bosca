package bosca.analytics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EventContext(
    @SerialName("app_id")
    val appId: String,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("client_id")
    val clientId: String? = null,
    val browser: Browser? = null,
    val device: Device,
    val geo: Geo? = null,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("user_id")
    val userId: String? = null
)