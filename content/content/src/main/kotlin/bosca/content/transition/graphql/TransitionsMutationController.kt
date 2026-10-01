package bosca.content.transition.graphql

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.model.Transition
import bosca.content.transition.model.TransitionInput
import bosca.content.transition.service.TransitionService
import bosca.content.transition.service.Transitioner
import bosca.di.ObjectProvider
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.content.configuration.MAX_BULK_OPERATION_SIZE
import bosca.serialization.UUID
import org.slf4j.LoggerFactory


object TransitionsMutation

@TypeController
class TransitionsMutationController(
    private val service: TransitionService,
    private val groupEvaluator: GroupEvaluator,
    private val metadataServiceProvider: ObjectProvider<MetadataService>,
    private val metadataPermissionEvaluatorProvider: ObjectProvider<MetadataPermissionEvaluator>,
    private val collectionServiceProvider: ObjectProvider<CollectionService>,
    private val collectionPermissionEvaluatorProvider: ObjectProvider<CollectionPermissionEvaluator>,
    private val transitioner: Transitioner
) : GraphQLController<TransitionsMutation> {

    companion object {
        private val log = LoggerFactory.getLogger(TransitionsMutationController::class.java)
    }

    @Field
    suspend fun add(authenticationContext: AuthenticationContext, transition: TransitionInput): Transition {
        groupEvaluator.verifyHasManagerGroup(authenticationContext)
        return service.add(transition)
    }

    @Field
    suspend fun edit(authenticationContext: AuthenticationContext, transition: TransitionInput): Transition {
        groupEvaluator.verifyHasManagerGroup(authenticationContext)
        return service.edit(transition)
    }

    @Field
    suspend fun delete(authenticationContext: AuthenticationContext, fromStateId: String, toStateId: String): Boolean {
        groupEvaluator.verifyHasManagerGroup(authenticationContext)
        service.delete(fromStateId, toStateId)
        return true
    }

    /**
     * Begin workflow state transitions for multiple metadata items in a single operation.
     *
     * Each item is individually permission-checked and transitioned. Items that do not exist,
     * are already in the target state, or that the caller lacks permission to edit are silently
     * skipped. Returns the number of items successfully transitioned.
     */
    @Field
    suspend fun beginTransitions(
        authenticationContext: AuthenticationContext,
        metadataIds: List<UUID>,
        stateId: String,
        status: String
    ): Int {
        require(metadataIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        val service = metadataServiceProvider.get()
        val evaluator = metadataPermissionEvaluatorProvider.get()
        val items = service.getByIds(metadataIds)
        val candidates = items.filter { it.workflowStateId != stateId }
        val allowed = evaluator.filterAllowed(authenticationContext, candidates, PermissionAction.EXECUTE)
        var succeeded = 0
        for (metadata in allowed) {
            try {
                val request = BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = stateId,
                    status = status
                )
                transitioner.beginTransition(authenticationContext, request, metadata)
                succeeded++
            } catch (e: Exception) {
                log.error("Failed to begin transition for metadata {}: {}", metadata.id, e.message)
            }
        }
        return succeeded
    }

    @Field
    suspend fun beginTransition(authenticationContext: AuthenticationContext, request: BeginTransitionInput): Boolean {
        if (request.metadataId != null) {
            val service = metadataServiceProvider.get()
            val evaluator = metadataPermissionEvaluatorProvider.get()
            val metadata = service.getById(request.metadataId ?: error("missing id"), request.version) ?: return false
            evaluator.verifyAllowed(authenticationContext, metadata, PermissionAction.EDIT)
            transitioner.beginTransition(authenticationContext, request)
            return true
        }
        if (request.collectionId != null) {
            val id = request.collectionId ?: error("missing id")
            val languageTag = request.languageTag
            val service = collectionServiceProvider.get()
            val evaluator = collectionPermissionEvaluatorProvider.get()
            val collection = if (languageTag != null) {
                service.getLanguageVariant(id, languageTag)
            } else {
                service.getById(id)
            } ?: return false
            evaluator.verifyAllowed(authenticationContext, collection, PermissionAction.EDIT)
            transitioner.beginTransition(authenticationContext, request)
            return true
        }
        TODO()
    }

    @Field
    suspend fun cancelTransition(authenticationContext: AuthenticationContext, metadataId: UUID?, metadataVersion: Int?, collectionId: UUID?, languageTag: String?): Boolean {
        if (metadataId != null) {
            val service = metadataServiceProvider.get()
            val evaluator = metadataPermissionEvaluatorProvider.get()
            val metadata = service.getById(metadataId, metadataVersion ?: 1) ?: return false
            evaluator.verifyAllowed(authenticationContext, metadata, PermissionAction.EDIT)
            transitioner.cancelLatestJob(authenticationContext, metadataId = metadataId, metadataVersion = metadataVersion)
            service.setPendingStateFailed(metadata, "Cancelled transition by user", authenticationContext.principal()?.asPrincipal())
            return true
        }
        if (collectionId != null) {
            val service = collectionServiceProvider.get()
            val evaluator = collectionPermissionEvaluatorProvider.get()
            val collection = if (languageTag != null) {
                service.getLanguageVariant(collectionId, languageTag)
            } else {
                service.getById(collectionId)
            } ?: return false
            evaluator.verifyAllowed(authenticationContext, collection, PermissionAction.EDIT)
            transitioner.cancelLatestJob(authenticationContext, collectionId = collectionId, languageTag = languageTag)
            service.setPendingStateFailed(collection, "Cancelled transition by user", authenticationContext.principal()?.asPrincipal())
            return true
        }
        TODO()
    }
}
