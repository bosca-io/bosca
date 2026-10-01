package bosca.segmentation.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for asynchronously delivering a campaign to its
 * segment audience. The job executor resolves the audience, builds channel-specific
 * messages, and dispatches them through the messaging infrastructure.
 */
@Serializable
data class SendCampaignJob(
    @Contextual
    val campaignId: UUID
) : IJobDefinition
