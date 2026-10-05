package bosca.content.transition.service

import bosca.content.collection.model.CollectionJobHistory
import bosca.content.collection.model.ContentItem
import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.model.JobHistory
import bosca.di.provide
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import org.slf4j.LoggerFactory

/**
 * Type-safe adapter that encapsulates all content-type-specific operations needed during
 * workflow transitions. Eliminates the repeated `when(item) { is Metadata -> ...; is ICollection -> ... }`
 * branching by providing a single polymorphic interface over metadata and collection operations.
 *
 * Methods that mutate state accept the current [ContentItem] as a parameter rather than
 * referencing a cached copy, because the item's state changes across the transition lifecycle
 * (e.g., after [setPendingState] the item has a pending ID that [setPendingStateComplete] reads).
 */
sealed interface ContentItemOperations {

    /** The content item as it was when the operations adapter was created. */
    val item: ContentItem

    /** The fallback job name used when a workflow state has no explicit job configured. */
    val fallbackJobName: String

    /** Verifies the caller has EXECUTE permission on the content item. */
    suspend fun verifyPermission(authentication: AuthenticationContext)

    /** Sets the pending workflow state, recording that a transition is in progress. */
    suspend fun setPendingState(principal: AuthenticatedPrincipal, request: BeginTransitionInput): ContentItem

    /** Finalizes the transition, moving the item into its target workflow state. */
    suspend fun setPendingStateComplete(current: ContentItem, principal: AuthenticatedPrincipal, request: BeginTransitionInput): ContentItem

    /** Rolls back a pending transition, leaving the item in its current state. */
    suspend fun setPendingStateFailed(current: ContentItem, principal: AuthenticatedPrincipal, status: String): ContentItem

    /** Records a job history entry for a transition job. */
    suspend fun addHistory(
        principal: AuthenticatedPrincipal,
        jobName: String,
        jobId: UUID,
        languageTag: String?,
        delayedUntil: OffsetDateTime?,
    ): JobHistory

    /** Cancels all active transition jobs and marks the pending transition as failed. */
    suspend fun cancelActiveJobs(authentication: AuthenticationContext)

    companion object {

        /**
         * Resolves the appropriate operations adapter for a content item, loading the item
         * from the database if not already provided.
         */
        suspend fun resolve(
            request: BeginTransitionInput,
            item: ContentItem?,
            metadataService: MetadataService,
            collectionService: CollectionService,
            metadataJobHistory: MetadataJobHistoryService,
            collectionJobHistory: CollectionJobHistoryService,
            metadataPermissionEvaluator: MetadataPermissionEvaluator,
            collectionPermissionEvaluator: CollectionPermissionEvaluator,
        ): ContentItemOperations {
            if (item != null) {
                return when (item) {
                    is Metadata -> MetadataOperations(item, metadataService, metadataJobHistory, metadataPermissionEvaluator)
                    is ICollection -> CollectionOperations(item, request.languageTag, collectionService, collectionJobHistory, collectionPermissionEvaluator)
                    else -> error("unsupported content item type: $item")
                }
            }
            request.metadataId?.let { id ->
                val version = request.version ?: error("a metadata version is required")
                val metadata = metadataService.getById(id, version) ?: error("metadata not found")
                return MetadataOperations(metadata, metadataService, metadataJobHistory, metadataPermissionEvaluator)
            }
            request.collectionId?.let { id ->
                val collection = if (request.languageTag != null) {
                    collectionService.getLanguageVariant(id, request.languageTag ?: error("missing language tag")) ?: error("variant not found")
                } else {
                    collectionService.getById(id) ?: error("collection not found")
                }
                return CollectionOperations(collection, request.languageTag, collectionService, collectionJobHistory, collectionPermissionEvaluator)
            }
            error("you must provide either a collection_id or a metadata_id")
        }
    }
}

private class MetadataOperations(
    private val metadata: Metadata,
    private val service: MetadataService,
    private val jobHistory: MetadataJobHistoryService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : ContentItemOperations {

    private val log = LoggerFactory.getLogger(MetadataOperations::class.java)

    override val item: ContentItem get() = metadata
    override val fallbackJobName: String get() = "transition-metadata"

    override suspend fun verifyPermission(authentication: AuthenticationContext) {
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EXECUTE)
    }

    override suspend fun setPendingState(principal: AuthenticatedPrincipal, request: BeginTransitionInput): ContentItem =
        service.setPendingState(
            metadata,
            toStateId = request.stateId,
            principal = principal.asPrincipal(),
            status = request.status,
            valid = request.stateValid
        )

    override suspend fun setPendingStateComplete(current: ContentItem, principal: AuthenticatedPrincipal, request: BeginTransitionInput): ContentItem =
        service.setPendingStateComplete(current as Metadata, request.status, principal = principal.asPrincipal())

    override suspend fun setPendingStateFailed(current: ContentItem, principal: AuthenticatedPrincipal, status: String): ContentItem =
        service.setPendingStateFailed(current as Metadata, status, principal = principal.asPrincipal())

    override suspend fun addHistory(
        principal: AuthenticatedPrincipal,
        jobName: String,
        jobId: UUID,
        languageTag: String?,
        delayedUntil: OffsetDateTime?,
    ): JobHistory = jobHistory.addHistory(
        MetadataJobHistory(
            id = metadata.id,
            version = metadata.version,
            jobName = jobName,
            jobId = jobId,
            principal = principal.asPrincipal().id,
            status = "initial queue",
            delayedUntil = delayedUntil,
        )
    )

    override suspend fun cancelActiveJobs(authentication: AuthenticationContext) {
        val principal = authentication.principal() ?: error("no principal")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EXECUTE)
        if (metadata.workflowStateId == "pending") {
            service.setNotReady(metadata)
        }
        for (activeJob in jobHistory.getActiveJobs(metadata.id, metadata.version)) {
            try {
                val factory = provide<JobConfigurationEnqueuer>(name = activeJob.jobName)
                factory.queue().markCancelled(activeJob.jobId)
            } catch (e: Exception) {
                log.warn("Failed to cancel job {} in queue: {}", activeJob.jobId, e.message, e)
            }
            jobHistory.setComplete(metadata.id, metadata.version, activeJob.jobId, "Cancelled", false)
        }
        service.setPendingStateFailed(metadata, "Cancelled Transition", principal = principal.asPrincipal())
    }
}

private class CollectionOperations(
    private val collection: ICollection,
    private val languageTag: String?,
    private val service: CollectionService,
    private val jobHistory: CollectionJobHistoryService,
    private val permissionEvaluator: CollectionPermissionEvaluator,
) : ContentItemOperations {

    private val log = LoggerFactory.getLogger(CollectionOperations::class.java)

    override val item: ContentItem get() = collection
    override val fallbackJobName: String get() = "transition-collection"

    override suspend fun verifyPermission(authentication: AuthenticationContext) {
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EXECUTE)
    }

    override suspend fun setPendingState(principal: AuthenticatedPrincipal, request: BeginTransitionInput): ContentItem =
        service.setPendingState(
            collection,
            toStateId = request.stateId,
            principal = principal.asPrincipal(),
            status = request.status,
            valid = request.stateValid
        )

    override suspend fun setPendingStateComplete(current: ContentItem, principal: AuthenticatedPrincipal, request: BeginTransitionInput): ContentItem =
        service.setPendingStateComplete(current as ICollection, request.status, principal = principal.asPrincipal())

    override suspend fun setPendingStateFailed(current: ContentItem, principal: AuthenticatedPrincipal, status: String): ContentItem =
        service.setPendingStateFailed(current as ICollection, status, principal = principal.asPrincipal())

    override suspend fun addHistory(
        principal: AuthenticatedPrincipal,
        jobName: String,
        jobId: UUID,
        languageTag: String?,
        delayedUntil: OffsetDateTime?,
    ): JobHistory = jobHistory.addHistory(
        CollectionJobHistory(
            id = collection.id,
            jobName = jobName,
            jobId = jobId,
            principal = principal.asPrincipal().id,
            languageTag = languageTag,
            status = "initial queue",
            delayedUntil = delayedUntil,
        )
    )

    override suspend fun cancelActiveJobs(authentication: AuthenticationContext) {
        val principal = authentication.principal() ?: error("no principal")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EXECUTE)
        if (collection.workflowStateId == "pending") {
            service.setNotReady(collection)
        }
        for (activeJob in jobHistory.getActiveJobs(collection.id)) {
            try {
                val factory = provide<JobConfigurationEnqueuer>(name = activeJob.jobName)
                factory.queue().markCancelled(activeJob.jobId)
            } catch (e: Exception) {
                log.warn("Failed to cancel job {} in queue: {}", activeJob.jobId, e.message, e)
            }
            jobHistory.setComplete(collection.id, activeJob.jobId, "Cancelled", false)
        }
        service.setPendingStateFailed(collection, "Cancelled Transition", principal = principal.asPrincipal())
    }
}
