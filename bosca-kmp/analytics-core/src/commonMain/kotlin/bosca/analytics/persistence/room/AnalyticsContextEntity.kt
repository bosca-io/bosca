package bosca.analytics.persistence.room

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import bosca.analytics.api.Device
import bosca.analytics.api.Geo

@Entity(tableName = "analytics_contexts")
internal data class AnalyticsContextEntity(
    @PrimaryKey
    val id: String,
    val appId: String,
    val appVersion: String,
    val clientId: String,
    @Embedded(prefix = "device_")
    val device: Device,
    @Embedded(prefix = "geo_")
    val geo: Geo,
    val browserAgent: String?,
    val sessionId: String,
    val userId: String?,
)
