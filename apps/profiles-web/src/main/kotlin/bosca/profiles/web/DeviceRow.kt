package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class DeviceRow(
    val id: String,
    val platform: String,
    val created: String,
    val lastCheckIn: String,
    val pushTokenCount: Int,
)
