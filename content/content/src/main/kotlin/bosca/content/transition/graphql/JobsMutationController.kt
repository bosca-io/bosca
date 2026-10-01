package bosca.content.transition.graphql

import bosca.content.collection.repository.CollectionJobHistoryRepository
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.repository.MetadataJobHistoryRepository
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.di.provide
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer

object JobsMutation

/**
 * GraphQL controller for the top-level `jobs` mutation type. Provides
 * operations for managing job executions across the system, independent
 * of content type or scheduling infrastructure.
 */
@TypeController
class JobsMutationController(
    private val groupEvaluator: GroupEvaluator,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val metadataJobHistoryRepository: MetadataJobHistoryRepository,
    private val metadataJobHistoryService: MetadataJobHistoryService,
    private val collectionService: CollectionService,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val collectionJobHistoryRepository: CollectionJobHistoryRepository,
    private val collectionJobHistoryService: CollectionJobHistoryService,
) : GraphQLController<JobsMutation> {

    /**
     * Cancels a job by its queue job ID and all sibling active jobs for the
     * same content item. A single workflow transition can create multiple
     * jobs (exit, enter, and default phases), so cancelling one job must
     * also cancel the others to avoid leaving orphaned jobs in the queue.
     *
     * Requires EXECUTE permission on the content item the job belongs to.
     */
    @Field
    suspend fun cancel(authenticationContext: AuthenticationContext, jobId: UUID): Boolean {
        val metadataJob = metadataJobHistoryRepository.getActiveJobByJobId(jobId)
        if (metadataJob != null) {
            val metadata = metadataService.getById(metadataJob.id, metadataJob.version)
                ?: error("metadata not found: ${metadataJob.id}")
            metadataPermissionEvaluator.verifyAllowed(authenticationContext, metadata, PermissionAction.EXECUTE)
            for (activeJob in metadataJobHistoryService.getActiveJobs(metadataJob.id, metadataJob.version)) {
                try {
                    val factory = provide<JobConfigurationEnqueuer>(name = activeJob.jobName)
                    factory.queue().markCancelled(activeJob.jobId)
                } catch (_: Exception) {
                    // job may already be gone from the queue
                }
                metadataJobHistoryService.setComplete(metadataJob.id, metadataJob.version, activeJob.jobId, "Cancelled", false)
            }
            return true
        }
        val collectionJob = collectionJobHistoryRepository.getActiveJobByJobId(jobId)
        if (collectionJob != null) {
            val collection = collectionService.getById(collectionJob.id)
                ?: error("collection not found: ${collectionJob.id}")
            collectionPermissionEvaluator.verifyAllowed(authenticationContext, collection, PermissionAction.EXECUTE)
            for (activeJob in collectionJobHistoryService.getActiveJobs(collectionJob.id)) {
                try {
                    val factory = provide<JobConfigurationEnqueuer>(name = activeJob.jobName)
                    factory.queue().markCancelled(activeJob.jobId)
                } catch (_: Exception) {
                    // job may already be gone from the queue
                }
                collectionJobHistoryService.setComplete(collectionJob.id, activeJob.jobId, "Cancelled", false)
            }
            return true
        }
        return false
    }
}
