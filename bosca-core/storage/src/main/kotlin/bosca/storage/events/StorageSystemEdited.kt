package bosca.storage.events

import bosca.events.annotation.JobEvent
import bosca.search.jobs.InitializeJob
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@JobEvent(jobs = [InitializeJob::class])
@Serializable
class StorageSystemEdited(override val id: UUID) : StorageSystemEvent {

}