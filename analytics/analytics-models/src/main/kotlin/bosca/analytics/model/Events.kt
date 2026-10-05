package bosca.analytics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Events(
    val context: EventContext? = null,
    val events: List<Event>,
    val sent: Long,
    @SerialName("sent_micros")
    val sentMicros: Long,
    val received: Long? = null,
    @SerialName("received_micros")
    val receivedMicros: Long? = null
)