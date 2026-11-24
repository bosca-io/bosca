package bosca.analytics.queue

import bosca.analytics.model.Events
import kotlinx.serialization.Serializable

@Serializable
internal data class DiskAnalyticsRecord(val events: Events, val created: Long)
