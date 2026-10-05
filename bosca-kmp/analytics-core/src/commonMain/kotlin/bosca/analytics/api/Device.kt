package bosca.analytics.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Device data shared by event ingestion and feature-flag targeting. */
@Serializable
data class Device(
    @SerialName("installation_id")
    val installationId: String,
    val manufacturer: String,
    val model: String,
    val platform: String,
    @SerialName("primary_locale")
    val primaryLocale: String,
    @SerialName("system_name")
    val systemName: String,
    val timezone: String,
    val type: String,
    val version: String,
)
