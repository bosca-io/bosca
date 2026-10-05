package bosca.sharedqueue

import bosca.serialization.UUID
import kotlin.time.Duration

interface Queue<T> {

    suspend fun enqueue(job: T): UUID

    suspend fun enqueueLater(job: T, timeout: Duration): UUID

    suspend fun dequeue(): T?
}