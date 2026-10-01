package bosca.content.collection.graphql

import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.transition.graphql.ContentJobHistory
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


class CollectionWorkflow(val collection: ICollection)

@TypeController
class CollectionWorkflowController(
    private val collectionService: CollectionService,
    private val collectionJobHistoryService: CollectionJobHistoryService,
) : GraphQLController<CollectionWorkflow> {

    @Field
    fun state(collection: CollectionWorkflow) = collection.collection.workflowStateId

    @Field
    fun stateValid(collection: CollectionWorkflow) = collection.collection.workflowStateValid

    @Field
    fun pending(collection: CollectionWorkflow) = collection.collection.workflowStatePendingId

    @Field
    fun deleteWorkflow(collection: CollectionWorkflow) = collection.collection.deleteWorkflowId

    @Field
    suspend fun running(collection: CollectionWorkflow): Int {
        return collectionJobHistoryService.getActiveJobs(collection.collection.id).size
    }

    @Field
    suspend fun activeJobs(collection: CollectionWorkflow): List<ContentJobHistory> {
        return collectionJobHistoryService
            .getActiveJobs(collection.collection.id)
            .map { ContentJobHistory(it) }
    }
}
