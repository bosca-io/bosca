package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.transition.graphql.ContentJobHistory
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


class MetadataWorkflow(val metadata: Metadata)

@TypeController
class MetadataWorkflowController(
    private val metadataJobHistoryService: MetadataJobHistoryService,
) : GraphQLController<MetadataWorkflow> {

    @Field
    fun state(metadata: MetadataWorkflow) = metadata.metadata.workflowStateId

    @Field
    fun stateValid(metadata: MetadataWorkflow) = metadata.metadata.workflowStateValid

    @Field
    fun pending(metadata: MetadataWorkflow) = metadata.metadata.workflowStatePendingId

    @Field
    fun deleteWorkflow(metadata: MetadataWorkflow) = metadata.metadata.deleteWorkflowId

    @Field
    suspend fun running(metadata: MetadataWorkflow): Int {
        return metadataJobHistoryService.getActiveJobs(metadata.metadata.id, metadata.metadata.version).size
    }

    @Field
    suspend fun activeJobs(metadata: MetadataWorkflow): List<ContentJobHistory> {
        return metadataJobHistoryService
            .getActiveJobs(metadata.metadata.id, metadata.metadata.version)
            .map { ContentJobHistory(it) }
    }
}
