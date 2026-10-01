package bosca.community.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ChatChannelJoinAgentJob(
    @Contextual
    val channelId: UUID,
    @Contextual
    val groupId: UUID?,
) : IJobDefinition
