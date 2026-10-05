package bosca.analytics.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

@Serializable
data class SqlJob(val sql: String) : IJobDefinition
