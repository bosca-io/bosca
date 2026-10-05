package bosca.cli.ci

import bosca.cli.api.Api
import bosca.cli.api.NetworkClient
import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlin.uuid.Uuid

open class CiApi(network: NetworkClient) : Api(network) {

    val serverUrl: String get() = network.url

    /**
     * Resolves the credential currently used by this API client.
     *
     * Ephemeral agents may receive their API token only through the process environment, without
     * a persisted [AgentConfig]. Build steps still need that credential for authenticated Git,
     * cache, storage, and artifact operations.
     */
    open suspend fun bearerToken(): String = network.tokenProvider?.invoke().orEmpty()

    open suspend fun registerAgent(
        name: String,
        labels: List<String>,
        mode: GitAgentMode,
    ) = network.boscaGraphql.execute(RegisterAgent, RegisterAgent.Variables(name, labels, mode)).git.registerAgent

    open suspend fun registerEphemeralAgent(
        jobId: Uuid,
        name: String,
        labels: List<String>,
        parentAgentId: Uuid,
        timeoutMinutes: Int,
    ) = network.boscaGraphql.execute(
        RegisterEphemeralAgent,
        RegisterEphemeralAgent.Variables(jobId, name, labels, parentAgentId, timeoutMinutes),
    ).git.registerEphemeralAgent

    open suspend fun deregisterAgent(id: Uuid): Boolean =
        network.boscaGraphql.execute(DeregisterAgent, DeregisterAgent.Variables(id)).git.deregisterAgent

    open suspend fun setAgentInstanceId(agentId: Uuid, instanceId: String): Boolean =
        network.boscaGraphql.execute(SetAgentInstanceId, SetAgentInstanceId.Variables(agentId, instanceId)).git.setAgentInstanceId

    open suspend fun agentHeartbeat(agentId: Uuid): Boolean =
        network.boscaGraphql.execute(AgentHeartbeat, AgentHeartbeat.Variables(agentId)).git.agentHeartbeat

    open suspend fun listAgents(status: GitAgentStatus? = null) =
        network.boscaGraphql.execute(ListPipelineAgents, ListPipelineAgents.Variables(status)).git.pipelineAgents

    open suspend fun getOrchestratorConfig(agentId: Uuid) =
        network.boscaGraphql.execute(GetOrchestratorConfig, GetOrchestratorConfig.Variables(agentId)).git.getOrchestratorConfig

    open suspend fun configureOrchestrator(agentId: Uuid, config: GitOrchestratorConfigInput) =
        network.boscaGraphql.execute(ConfigureOrchestrator, ConfigureOrchestrator.Variables(agentId, config)).git.configureOrchestrator

    open suspend fun claimJob(agentId: Uuid, labels: List<String>) =
        network.boscaGraphql.execute(ClaimJob, ClaimJob.Variables(agentId, labels, null)).git.claimJob

    open suspend fun claimJobById(agentId: Uuid, jobId: Uuid) =
        network.boscaGraphql.execute(ClaimJob, ClaimJob.Variables(agentId, emptyList(), jobId)).git.claimJob

    open suspend fun targetedJobStatus(agentId: Uuid, jobId: Uuid, attempt: Int? = null) =
        network.boscaGraphql.execute(
            TargetedJobStatus,
            TargetedJobStatus.Variables(agentId, jobId, attempt),
        ).git.targetedJobStatus

    open suspend fun updateJobStatus(jobId: Uuid, status: GitPipelineRunStatus, errorMessage: String? = null) =
        network.boscaGraphql.execute(UpdateJobStatus, UpdateJobStatus.Variables(jobId, status, errorMessage)).git.updateJobStatus

    open suspend fun updateStepStatus(stepId: Uuid, status: GitPipelineRunStatus, exitCode: Int? = null, errorMessage: String? = null) =
        network.boscaGraphql.execute(
            UpdateStepStatus,
            UpdateStepStatus.Variables(stepId, status, exitCode, errorMessage),
        ).git.updateStepStatus

    open suspend fun reportCommitStatus(
        repositoryId: Uuid,
        commitSha: String,
        context: String,
        state: GitCommitStatusState,
        description: String? = null,
        targetUrl: String? = null,
        jobId: Uuid,
    ) = network.boscaGraphql.execute(
        ReportCommitStatus,
        ReportCommitStatus.Variables(repositoryId, commitSha, context, state, description, targetUrl, jobId),
    ).git.reportCommitStatus

    open suspend fun appendPipelineLogs(
        repositoryId: Uuid,
        runId: Uuid,
        jobId: Uuid,
        stepId: Uuid,
        lines: List<LogLineInput>,
    ) = network.boscaGraphql.execute(
        AppendPipelineLogs,
        AppendPipelineLogs.Variables(repositoryId, runId, jobId, stepId, lines),
    ).git.appendPipelineLogs

    open suspend fun decryptPipelineSecrets(repositoryId: Uuid, names: List<String>) =
        network.boscaGraphql.execute(DecryptPipelineSecrets, DecryptPipelineSecrets.Variables(repositoryId, names)).git.decryptPipelineSecrets

    open suspend fun createReleaseTag(jobId: Uuid, repository: String, tag: String?, message: String?) =
        network.boscaGraphql.execute(CreateReleaseTag, CreateReleaseTag.Variables(jobId, repository, tag, message)).git.createReleaseTag

    open suspend fun allocateBuildNumberFromJob(
        jobId: Uuid,
        platform: String,
        applicationId: String,
        sourceVersion: String,
        buildKey: String?,
        minimum: String?,
    ) = network.boscaGraphql.execute(
        AllocateBuildNumberFromJob,
        AllocateBuildNumberFromJob.Variables(jobId, platform, applicationId, sourceVersion, buildKey, minimum),
    ).git.allocateBuildNumberFromJob

    open suspend fun resolveJobSecrets(jobId: Uuid) =
        network.boscaGraphql.execute(ResolveJobSecrets, ResolveJobSecrets.Variables(jobId)).git.resolveJobSecrets

    open suspend fun deployFromJob(
        jobId: Uuid,
        environmentKey: String,
        repository: String?,
        target: String?,
        overrides: kotlinx.serialization.json.JsonElement?,
    ) = network.boscaGraphql.execute(
        DeployFromJob,
        DeployFromJob.Variables(jobId, environmentKey, repository, target, overrides),
    ).git.deployFromJob

    open suspend fun rollbackFromJob(
        jobId: Uuid,
        environmentKey: String,
        repository: String?,
        target: String?,
        toRevision: Int?,
        overrides: kotlinx.serialization.json.JsonElement?,
    ) = network.boscaGraphql.execute(
        RollbackFromJob,
        RollbackFromJob.Variables(jobId, environmentKey, repository, target, toRevision, overrides),
    ).git.rollbackFromJob

    open suspend fun playRolloutFromJob(
        jobId: Uuid,
        environmentKey: String,
        rolloutPercentage: Double,
        repository: String?,
        target: String?,
    ) = network.boscaGraphql.execute(
        PlayRolloutFromJob,
        PlayRolloutFromJob.Variables(jobId, environmentKey, rolloutPercentage, repository, target),
    ).git.playRolloutFromJob

    open suspend fun appStoreReviewFromJob(
        jobId: Uuid,
        environmentKey: String,
        mode: GitReleaseAppStoreReviewMode,
        repository: String?,
        target: String?,
    ) = network.boscaGraphql.execute(
        AppStoreReviewFromJob,
        AppStoreReviewFromJob.Variables(jobId, environmentKey, mode, repository, target),
    ).git.appStoreReviewFromJob

    open suspend fun storeHealthFromJob(
        jobId: Uuid,
        environmentKey: String,
        maxCrashRate: Double,
        windowSeconds: Long,
        repository: String?,
        target: String?,
    ) = network.boscaGraphql.execute(
        StoreHealthFromJob,
        StoreHealthFromJob.Variables(jobId, environmentKey, maxCrashRate, windowSeconds, repository, target),
    ).git.storeHealthFromJob

    open suspend fun markReleasedFromJob(jobId: Uuid) =
        network.boscaGraphql.execute(MarkReleasedFromJob, MarkReleasedFromJob.Variables(jobId)).git.markReleasedFromJob

    open suspend fun generateReleaseNotesFromJob(jobId: Uuid) =
        network.boscaGraphql.execute(
            GenerateReleaseNotesFromJob,
            GenerateReleaseNotesFromJob.Variables(jobId),
        ).git.generateReleaseNotesFromJob

    open suspend fun jobDeploymentHealth(jobId: Uuid, environmentKey: String, repository: String?) =
        network.boscaGraphql.execute(
            JobDeploymentHealth,
            JobDeploymentHealth.Variables(jobId, environmentKey, repository),
        ).git.jobDeploymentHealth

    open suspend fun getPipelineRun(id: Uuid) =
        network.boscaGraphql.execute(GetPipelineRun, GetPipelineRun.Variables(id)).git.pipelineRun

    open suspend fun listPipelineRuns(repositoryId: Uuid, offset: Long? = null, limit: Int? = null) =
        network.boscaGraphql.execute(ListPipelineRuns, ListPipelineRuns.Variables(repositoryId, offset, limit)).git.pipelineRuns

    open suspend fun getPipelineLogs(stepId: Uuid, offset: Int? = null, limit: Int? = null) =
        network.boscaGraphql.execute(GetPipelineLogs, GetPipelineLogs.Variables(stepId, offset, limit)).git.pipelineLogs

    open suspend fun listPipelines(repositoryId: Uuid) =
        network.boscaGraphql.execute(ListPipelines, ListPipelines.Variables(repositoryId)).git.pipelines

    open suspend fun triggerPipeline(pipelineId: Uuid, ref: String) =
        network.boscaGraphql.execute(TriggerPipeline, TriggerPipeline.Variables(pipelineId, ref)).git.triggerPipeline

    open suspend fun cancelPipelineRun(id: Uuid) =
        network.boscaGraphql.execute(CancelPipelineRun, CancelPipelineRun.Variables(id)).git.cancelPipelineRun

    open suspend fun cancelPipelineJob(jobId: Uuid) =
        network.boscaGraphql.execute(CancelPipelineJob, CancelPipelineJob.Variables(jobId)).git.cancelPipelineJob

    open suspend fun rerunPipeline(runId: Uuid) =
        network.boscaGraphql.execute(RerunPipeline, RerunPipeline.Variables(runId)).git.rerunPipeline

    open suspend fun rerunPipelineJob(jobId: Uuid) =
        network.boscaGraphql.execute(RerunPipelineJob, RerunPipelineJob.Variables(jobId)).git.rerunPipelineJob

    open suspend fun setPipelineSecret(repositoryId: Uuid, name: String, value: String) =
        network.boscaGraphql.execute(SetPipelineSecret, SetPipelineSecret.Variables(repositoryId, name, value)).git.setPipelineSecret

    open suspend fun deletePipelineSecret(repositoryId: Uuid, name: String): Boolean =
        network.boscaGraphql.execute(DeletePipelineSecret, DeletePipelineSecret.Variables(repositoryId, name)).git.deletePipelineSecret

    open suspend fun listPipelineSecrets(repositoryId: Uuid) =
        network.boscaGraphql.execute(ListPipelineSecrets, ListPipelineSecrets.Variables(repositoryId)).git.pipelineSecrets

    open suspend fun getRepositoryById(id: Uuid) =
        network.boscaGraphql.execute(GetRepositoryById, GetRepositoryById.Variables(id)).git.repositoryById
}
