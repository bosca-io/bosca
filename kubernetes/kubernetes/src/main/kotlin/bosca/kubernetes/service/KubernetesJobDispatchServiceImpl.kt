package bosca.kubernetes.service

import bosca.db.afterCommit
import bosca.db.connectionOrNull
import bosca.db.withConnectionManager
import bosca.di.annotation.ProviderName
import bosca.kubernetes.jobs.KubernetesJobQueueNames
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.jobs.prepareDispatchJob
import bosca.kubernetes.model.KubernetesJobExecution
import bosca.kubernetes.model.KubernetesJobResult
import bosca.kubernetes.model.toResultOrNull
import bosca.kubernetes.repository.KubernetesJobExecutionRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue
import kotlinx.serialization.json.Json

@ServiceImplementation
class KubernetesJobDispatchServiceImpl(
    @ProviderName(KubernetesJobQueueNames.jobQueue)
    private val queue: JobQueue,
    private val executions: KubernetesJobExecutionRepository,
    private val json: Json,
) : KubernetesJobDispatchService {

    override suspend fun dispatch(request: KubernetesJobRequest): UUID {
        request.validate()
        val proposedId = UUID.random()
        val serializedRequest = json.encodeToJsonElement(KubernetesJobRequest.serializer(), request)
        val execution = executions.create(
            dispatchId = proposedId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
            request = serializedRequest,
        )
        if (execution.dispatchId == proposedId) {
            val needsFreshConnection = connectionOrNull()?.inTransaction == true
            afterCommit {
                queue.enqueue(request.prepareDispatchJob(proposedId))
                if (needsFreshConnection) {
                    withConnectionManager { executions.markPublished(proposedId) }
                } else {
                    executions.markPublished(proposedId)
                }
            }
        }
        return execution.dispatchId
    }

    override suspend fun getExecution(dispatchId: UUID): KubernetesJobExecution? =
        executions.getById(dispatchId)

    override suspend fun getResult(dispatchId: UUID): KubernetesJobResult? =
        executions.getById(dispatchId)?.toResultOrNull()

    override suspend fun cancel(dispatchId: UUID) {
        executions.requestCancellation(dispatchId)
        afterCommit { queue.markCancelled(dispatchId) }
    }
}
