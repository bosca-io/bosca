package bosca.segmentation.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a campaign, including its delivery
 * channel, content payload, target segments, and optional schedule.
 */
@Serializable
data class CampaignInput(
    val name: String,
    val channel: NotificationChannel,
    @Contextual
    val content: JsonElement? = null,
    val segmentIds: List<@Contextual UUID>,
    @Contextual
    val scheduledAt: OffsetDateTime? = null,
    @Contextual
    val endedAt: OffsetDateTime? = null,
    val placement: String? = null,
    val weight: Int = 0
)
