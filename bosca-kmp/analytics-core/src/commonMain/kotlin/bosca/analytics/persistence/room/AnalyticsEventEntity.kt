package bosca.analytics.persistence.room

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "analytics_events",
    foreignKeys = [
        ForeignKey(
            entity = AnalyticsContextEntity::class,
            parentColumns = ["id"],
            childColumns = ["contextId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("namespace", "created", "clientId"), Index("contextId")],
)
internal data class AnalyticsEventEntity(
    @PrimaryKey
    val clientId: String,
    val namespace: String,
    val contextId: String,
    val type: String,
    val created: Long,
    val createdMicros: Int,
    val elementPayload: String,
    val pagePayload: String?,
    val errorPayload: String?,
)
