package bosca.workops.model.notification

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

@Serializable
data class NotificationDeliveryJob(
    val delivery: NotificationDelivery,
) : IJobDefinition
