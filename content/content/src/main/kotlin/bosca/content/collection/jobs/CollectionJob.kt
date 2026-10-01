package bosca.content.collection.jobs

import bosca.queue.annotations.ICollectionJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class CollectionJob(
    @Contextual
    override val id: UUID
) : ICollectionJobDefinition {
}