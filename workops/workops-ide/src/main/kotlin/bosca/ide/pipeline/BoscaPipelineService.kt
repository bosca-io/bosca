package bosca.ide.pipeline

import bosca.ide.server.BoscaConnectionManager
import bosca.ide.server.BoscaSubscription
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

/** Server-scoped CI queries and mutations used by the pipeline tool-window view. */
@Service(Service.Level.PROJECT)
class BoscaPipelineService(project: Project) {
    private val connections = project.getService(BoscaConnectionManager::class.java)

    fun pipelines(serverProfileId: String, repositoryId: String): CompletableFuture<List<BoscaPipeline>> {
        val variables = variables("repositoryId" to repositoryId)
        return connections.execute(serverProfileId, PIPELINES_QUERY, variables).thenApply { data ->
            data.getAsJsonObject("git").getAsJsonArray("pipelines").map { element ->
                BoscaPipelineJson.parsePipeline(serverProfileId, repositoryId, element.asJsonObject)
            }
        }
    }

    fun logs(
        serverProfileId: String,
        stepId: String,
        limit: Int = 1000,
    ): CompletableFuture<List<BoscaPipelineLogLine>> =
        connections.execute(
            serverProfileId,
            LOGS_QUERY,
            variables("stepId" to stepId, "limit" to limit),
        ).thenApply { data ->
            data.getAsJsonObject("git").getAsJsonArray("pipelineLogs").map { BoscaPipelineJson.parseLogLine(it.asJsonObject) }
        }

    fun subscribeLogs(
        serverProfileId: String,
        repositoryId: String,
        stepId: String,
        onNext: (BoscaPipelineLogLine) -> Unit,
        onError: (Throwable) -> Unit,
    ): BoscaSubscription {
        return connections.subscribe(
            profileId = serverProfileId,
            document = LOGS_SUBSCRIPTION,
            variables = variables("repositoryId" to repositoryId, "stepId" to stepId),
            onNext = { data ->
                data.getAsJsonObject("pipelineStepLogs")?.let { onNext(BoscaPipelineJson.parseLogLine(it)) }
            },
            onError = onError,
        )
    }

    fun trigger(serverProfileId: String, pipelineId: String, ref: String) = mutation(
        serverProfileId,
        TRIGGER_MUTATION,
        variables("pipelineId" to pipelineId, "ref" to ref),
    )

    fun cancelRun(serverProfileId: String, runId: String) = mutation(
        serverProfileId, CANCEL_RUN_MUTATION, variables("id" to runId)
    )

    fun rerun(serverProfileId: String, runId: String) = mutation(
        serverProfileId, RERUN_MUTATION, variables("id" to runId)
    )

    fun rerunFailed(serverProfileId: String, runId: String) = mutation(
        serverProfileId, RERUN_FAILED_MUTATION, variables("id" to runId)
    )

    fun cancelJob(serverProfileId: String, jobId: String) = mutation(
        serverProfileId, CANCEL_JOB_MUTATION, variables("id" to jobId)
    )

    fun rerunJob(serverProfileId: String, jobId: String) = mutation(
        serverProfileId, RERUN_JOB_MUTATION, variables("id" to jobId)
    )

    fun approveJob(serverProfileId: String, jobId: String, comment: String?) = mutation(
        serverProfileId, APPROVE_JOB_MUTATION, variables("id" to jobId, "comment" to comment)
    )

    fun rejectJob(serverProfileId: String, jobId: String, comment: String?) = mutation(
        serverProfileId, REJECT_JOB_MUTATION, variables("id" to jobId, "comment" to comment)
    )

    private fun mutation(serverProfileId: String, query: String, variables: JsonObject): CompletableFuture<Boolean> =
        connections.execute(serverProfileId, query, variables).thenApply { true }

    private fun variables(vararg values: Pair<String, Any?>) = JsonObject().apply {
        values.forEach { (key, value) ->
            when (value) {
                null -> add(key, com.google.gson.JsonNull.INSTANCE)
                is Int -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                else -> addProperty(key, value.toString())
            }
        }
    }

    companion object {
        private const val RUN_FIELDS = """
            id number status ref commitSha triggerType created started finished durationSeconds
            artifacts { id name sizeBytes created }
            jobs {
              id name status runnerLabel attempt awaitingRequirements awaitingApproval approvalRequired
              errorMessage started finished
              steps { id name ordinal status exitCode errorMessage started finished }
            }
        """
        private const val PIPELINES_QUERY = """
            query BoscaIdePipelines(${ '$' }repositoryId: UUID!) {
              git { pipelines(repositoryId: ${ '$' }repositoryId) {
                id name filePath triggerTypes
                runs(offset: 0, limit: 50) { $RUN_FIELDS }
              } }
            }
        """
        private const val LOGS_QUERY = """
            query BoscaIdePipelineLogs(${ '$' }stepId: UUID!, ${ '$' }limit: Int!) {
              git { pipelineLogs(stepId: ${ '$' }stepId, limit: ${ '$' }limit, tail: true) {
                lineNumber timestamp content stream
              } }
            }
        """
        private const val LOGS_SUBSCRIPTION = """
            subscription BoscaIdePipelineStepLogs(${ '$' }repositoryId: UUID!, ${ '$' }stepId: UUID!) {
              pipelineStepLogs(repositoryId: ${ '$' }repositoryId, stepId: ${ '$' }stepId) {
                lineNumber timestamp content stream
              }
            }
        """
        private const val TRIGGER_MUTATION = """
            mutation BoscaIdeTriggerPipeline(${ '$' }pipelineId: UUID!, ${ '$' }ref: String!) {
              git { triggerPipeline(pipelineId: ${ '$' }pipelineId, ref: ${ '$' }ref) { id } }
            }
        """
        private const val CANCEL_RUN_MUTATION = "mutation BoscaIdeCancelRun(${'$'}id: UUID!) { git { cancelPipelineRun(id: ${'$'}id) { id } } }"
        private const val RERUN_MUTATION = "mutation BoscaIdeRerun(${'$'}id: UUID!) { git { rerunPipeline(runId: ${'$'}id) { id } } }"
        private const val RERUN_FAILED_MUTATION = "mutation BoscaIdeRerunFailed(${'$'}id: UUID!) { git { rerunFailedJobs(runId: ${'$'}id) { id } } }"
        private const val CANCEL_JOB_MUTATION = "mutation BoscaIdeCancelJob(${'$'}id: UUID!) { git { cancelPipelineJob(jobId: ${'$'}id) { id } } }"
        private const val RERUN_JOB_MUTATION = "mutation BoscaIdeRerunJob(${'$'}id: UUID!) { git { rerunPipelineJob(jobId: ${'$'}id) { id } } }"
        private const val APPROVE_JOB_MUTATION = "mutation BoscaIdeApproveJob(${'$'}id: UUID!, ${'$'}comment: String) { git { approvePipelineJob(jobId: ${'$'}id, comment: ${'$'}comment) { id } } }"
        private const val REJECT_JOB_MUTATION = "mutation BoscaIdeRejectJob(${'$'}id: UUID!, ${'$'}comment: String) { git { rejectPipelineJob(jobId: ${'$'}id, comment: ${'$'}comment) { id } } }"
    }
}
