package bosca.analytics.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Bible usage awaiting server-side token resolution; the payload contains no provider token. */
@Serializable
data class FumsDeliveryJob(
    @Contextual val bibleId: UUID,
    val version: Int,
    val variant: String,
    val usfm: String,
    @Contextual val audioId: UUID?,
    val deviceId: String,
    val sessionId: String,
    val userId: String?,
) : IJobDefinition
