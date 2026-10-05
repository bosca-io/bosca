package bosca.content.transition.jobs

import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.content.transition.service.Transitioner
import bosca.db.transaction
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.job

@JobDefinition(CollectionTransitionJob::class, JobQueueNames.contentJobQueue, "transition-collection")
class CollectionTransitionExecutor(
    private val collectionService: CollectionService,
    private val collectionJobHistoryService: CollectionJobHistoryService,
    private val transitioner: Transitioner,
    private val securityService: SecurityService
) : AbstractJobExecutor<CollectionTransitionJob>(CollectionTransitionJob.serializer()) {

    override suspend fun getLockId(): String {
        val job = getJobDefinition()
        return if (job.languageTag != null) "${job.id}.${job.languageTag}" else job.id.toString()
    }

    override suspend fun execute() {
        val config = getJobDefinition()
        collectionJobHistoryService.setStatus(config.id, job().getId(), "Running")
        collectionService.removeFromCache(config.id)
        executeTransition(config)
    }

    private suspend fun executeTransition(config: CollectionTransitionJob) = transaction {
        val languageTag = config.languageTag
        val collection = if (languageTag == null) {
            collectionService.getById(config.id)
                ?: error("Collection not found: ${config.id}")
        } else {
            collectionService.getLanguageVariant(config.id, languageTag)
                ?: error("Variant not found: ${config.id}")
        }
        val auth = securityService.impersonate("sa")
        val principal = auth.principal().asPrincipal()

        ContentTransitionLogic.execute(
            item = collection,
            principal = principal,
            authenticationContext = auth,
            transitioner = transitioner,
            transitioningService = collectionService,
            getWorkflowStateValid = { it.workflowStateValid },
            languageTag = languageTag,
        )
    }
}
