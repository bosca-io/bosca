package bosca.sharedqueue.jobs.enqueue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface JobEnqueueEventChannel {

    suspend fun emit(event: JobEnqueueEvent)

    fun events(): Flow<JobEnqueueEvent>
}

class DefaultJobEnqueueEventChannel : JobEnqueueEventChannel {

    private val flow = MutableSharedFlow<JobEnqueueEvent>(extraBufferCapacity = 256)

    override suspend fun emit(event: JobEnqueueEvent) {
        flow.emit(event)
    }

    override fun events(): Flow<JobEnqueueEvent> = flow.asSharedFlow()
}
