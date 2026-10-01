package bosca.workops.model.notification

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Domain command describing one notification event independently of queue infrastructure. */
@Serializable
data class NotificationDelivery(
    @Contextual val id: UUID = UUID.random(),
    val event: NotificationEvent,
    @Contextual val taskId: UUID? = null,
    @Contextual val specId: UUID? = null,
    @Contextual val requirementId: UUID? = null,
    @Contextual val projectId: UUID? = null,
    @Contextual val actorProfileId: UUID? = null,
    val mentionedProfileIds: Set<@Contextual UUID> = emptySet(),
    val commentId: Long? = null,
    @Contextual val assigneeProfileId: UUID? = null,
    val assigneeChanged: Boolean = false,
    @Contextual val fromStatusId: UUID? = null,
    @Contextual val toStatusId: UUID? = null,
)
