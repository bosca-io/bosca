package bosca.content.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

@Serializable
class FinalizeDeletionJob : IJobDefinition
