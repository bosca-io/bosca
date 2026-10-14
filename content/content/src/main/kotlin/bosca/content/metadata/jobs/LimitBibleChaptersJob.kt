package bosca.content.metadata.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Trims the current retained chapter responses for an upstream edition. */
@Serializable
data class LimitBibleChaptersJob(
    val provider: String,
    val providerBibleId: String,
) : IJobDefinition
