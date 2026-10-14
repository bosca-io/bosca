package bosca.content.metadata.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Trims retained chapter recordings for one upstream edition. */
@Serializable
data class LimitBibleAudioChaptersJob(val provider: String, val providerBibleId: String) : IJobDefinition
