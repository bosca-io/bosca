package bosca.content.transition.jobs

import bosca.content.collection.model.ContentItem
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.transition.service.Transitioner
import bosca.content.transition.service.TransitioningService
import bosca.events.DisabledEventManagerFilter
import bosca.events.eventManager
import bosca.events.withEventManager
import bosca.queue.annotations.JobDefinition
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.job

/**
 * Wraps [MetadataService] to suppress event dispatch during executor-internal state mutations.
 * Events fired by intermediate states (processing→draft dance) would trigger cascading jobs
 * that interfere with the transition in progress.
 */
private class EventSuppressingMetadataTransitioningService(
    private val delegate: MetadataService
) : TransitioningService<Metadata> {

    override suspend fun setPendingState(
        item: Metadata, toStateId: String, status: String,
        valid: OffsetDateTime?, principal: Principal?, notifyEvent: Boolean
    ): Metadata = withEventManager {
        eventManager().filter = DisabledEventManagerFilter
        delegate.setPendingState(item, toStateId, status, valid, principal, notifyEvent)
    }

    override suspend fun setPendingStateComplete(item: Metadata, status: String, principal: Principal?): Metadata =
        withEventManager {
            eventManager().filter = DisabledEventManagerFilter
            delegate.setPendingStateComplete(item, status, principal)
        }

    override suspend fun setPendingStateFailed(item: Metadata, status: String, principal: Principal?): Metadata =
        withEventManager {
            eventManager().filter = DisabledEventManagerFilter
            delegate.setPendingStateFailed(item, status, principal)
        }

    override suspend fun setState(item: Metadata, toStateId: String, status: String, principal: Principal?): Metadata =
        withEventManager {
            eventManager().filter = DisabledEventManagerFilter
            delegate.setState(item, toStateId, status, principal)
        }
}

@JobDefinition(MetadataTransitionJob::class, JobQueueNames.contentJobQueue, "transition-metadata")
class MetadataTransitionExecutor(
    private val metadataService: MetadataService,
    private val metadataJobHistoryService: MetadataJobHistoryService,
    private val transitioner: Transitioner,
    private val securityService: SecurityService
) : AbstractJobExecutor<MetadataTransitionJob>(MetadataTransitionJob.serializer()) {

    override suspend fun getLockId(): String = getJobDefinition().id.toString()

    override suspend fun execute() {
        val config = getJobDefinition()
        metadataJobHistoryService.setStatus(config.id, config.version, job().getId(), "Running")
        metadataService.removeFromCache(config.id, config.version)
        val metadata = metadataService.getById(config.id, config.version) ?: error("Metadata not found: ${config.id}")
        val auth = securityService.impersonate("sa")
        execute(metadataService, transitioner, metadata, auth)
    }

    companion object {

        suspend fun execute(
            metadataService: MetadataService,
            transitioner: Transitioner,
            metadata: Metadata,
            authenticationContext: AuthenticationContext,
        ): ContentItem {
            val principal = authenticationContext.principal()?.asPrincipal() ?: error("no principal")
            return ContentTransitionLogic.execute(
                item = metadata,
                principal = principal,
                authenticationContext = authenticationContext,
                transitioner = transitioner,
                transitioningService = EventSuppressingMetadataTransitioningService(metadataService),
                completionService = metadataService,
                getWorkflowStateValid = { it.workflowStateValid },
                languageTag = null,
            )
        }
    }
}
