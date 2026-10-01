package bosca.collaboration.jobs

import bosca.collaboration.events.FederationTarget
import bosca.communications.model.MessageContent
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Job payload for relaying a Bosca chat message across federation links.
 * Carries everything the executor needs in one shot so the executor doesn't
 * have to re-fetch the message from JetStream or re-query federation links.
 */
@Serializable
data class FederationOutboundJob(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val senderName: String,
    val targets: List<FederationTarget>,
    val attributes: JsonElement? = null,
) : IJobDefinition
