package bosca.sharedqueue.jobs

import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

interface JobConfigurationEnqueuer {

    val queueName: String

    val displayName: String
        get() = ""

    suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit = {}): Job

    suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit = {}): Job

    suspend fun enqueueLater(configuration: JsonElement, timeout: Duration, initializer: suspend Job.() -> Unit = {}): Job

    suspend fun queue(): JobQueue
}