package bosca.git.ci.graphql

import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.AlertSink
import bosca.git.model.OrchestratorConfig
import bosca.git.model.Pipeline
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineArtifact
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineSecret
import bosca.git.model.PipelineStep
import bosca.git.model.PipelineTriggerType
import bosca.git.model.ProviderCredentials
import bosca.git.model.VmDefaults
import bosca.git.service.LogLine
import bosca.git.service.LogStream
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@TypeController(type = "GitPipelineAgent")
class GitPipelineAgentController(
    private val jobService: bosca.git.service.PipelineJobService
) : GraphQLController<PipelineAgent> {

    companion object {
        private const val HEARTBEAT_STALE_SECONDS = 300L
    }

    @Field
    fun id(agent: PipelineAgent): UUID = agent.id

    @Field
    fun name(agent: PipelineAgent): String = agent.name

    @Field
    fun labels(agent: PipelineAgent): List<String> = agent.labels

    @Field
    fun mode(agent: PipelineAgent): AgentMode = agent.mode

    @Field
    fun status(agent: PipelineAgent): AgentStatus {
        if (agent.status == AgentStatus.ONLINE || agent.status == AgentStatus.BUSY) {
            val heartbeat = agent.lastHeartbeat ?: return AgentStatus.OFFLINE
            val staleThreshold = OffsetDateTime.now().minusSeconds(HEARTBEAT_STALE_SECONDS)
            if (heartbeat.isBefore(staleThreshold)) {
                return AgentStatus.OFFLINE
            }
        }
        return agent.status
    }

    @Field
    fun ephemeral(agent: PipelineAgent): Boolean = agent.ephemeral

    @Field
    fun jobId(agent: PipelineAgent): UUID? = agent.jobId

    @Field
    fun parentAgentId(agent: PipelineAgent): UUID? = agent.parentAgentId

    @Field
    fun instanceId(agent: PipelineAgent): String? = agent.instanceId

    @Field
    fun lastHeartbeat(agent: PipelineAgent): OffsetDateTime? = agent.lastHeartbeat

    @Field
    fun expiresAt(agent: PipelineAgent): OffsetDateTime? = agent.expiresAt

    @Field
    fun created(agent: PipelineAgent): OffsetDateTime = agent.created

    @Field
    suspend fun currentJob(agent: PipelineAgent): PipelineJob? {
        return jobService.findCurrentByAgent(agent.id)
    }

    @Field
    suspend fun recentJobs(agent: PipelineAgent, limit: Int?): List<PipelineJob> {
        return jobService.findByAgent(agent.id, limit?.coerceIn(1, 50) ?: 10)
    }
}

@TypeController(type = "GitPipelineArtifact")
class GitPipelineArtifactController : GraphQLController<PipelineArtifact> {

    @Field
    fun id(artifact: PipelineArtifact): UUID = artifact.id

    @Field
    fun repositoryId(artifact: PipelineArtifact): UUID = artifact.repositoryId

    @Field
    fun pipelineRunId(artifact: PipelineArtifact): UUID = artifact.pipelineRunId

    @Field
    fun runNumber(artifact: PipelineArtifact): Int = artifact.runNumber

    @Field
    fun name(artifact: PipelineArtifact): String = artifact.name

    @Field
    fun sizeBytes(artifact: PipelineArtifact): Long = artifact.sizeBytes

    @Field
    fun created(artifact: PipelineArtifact): OffsetDateTime = artifact.created
}

@TypeController(type = "GitPipelineStep")
class GitPipelineStepController : GraphQLController<PipelineStep> {

    @Field
    fun id(step: PipelineStep): UUID = step.id

    @Field
    fun pipelineJobId(step: PipelineStep): UUID = step.pipelineJobId

    @Field
    fun name(step: PipelineStep): String = step.name

    @Field
    fun ordinal(step: PipelineStep): Int = step.ordinal

    @Field
    fun status(step: PipelineStep): PipelineRunStatus = step.status

    @Field
    fun uses(step: PipelineStep): String? = step.uses

    @Field
    fun run(step: PipelineStep): String? = step.run

    @Field
    fun image(step: PipelineStep): String? = step.image

    @Field
    fun condition(step: PipelineStep): String? = step.condition

    @Field
    fun workingDirectory(step: PipelineStep): String? = step.workingDirectory

    @Field
    fun with(step: PipelineStep): JsonElement = step.with

    @Field
    fun env(step: PipelineStep): JsonElement = step.env

    @Field
    fun exitCode(step: PipelineStep): Int? = step.exitCode

    @Field
    fun errorMessage(step: PipelineStep): String? = step.errorMessage

    @Field
    fun started(step: PipelineStep): OffsetDateTime? = step.started

    @Field
    fun finished(step: PipelineStep): OffsetDateTime? = step.finished
}

@TypeController(type = "GitPipelineSecretInfo")
class GitPipelineSecretInfoController : GraphQLController<PipelineSecret> {

    @Field
    fun name(secret: PipelineSecret): String = secret.name

    @Field
    fun id(secret: PipelineSecret): bosca.serialization.UUID = secret.id

    @Field
    fun environmentKey(secret: PipelineSecret): String? = secret.environmentKey

    @Field
    fun created(secret: PipelineSecret): OffsetDateTime = secret.created

    @Field
    fun updated(secret: PipelineSecret): OffsetDateTime = secret.updated
}

@TypeController(type = "GitPipelineLogLine")
class GitPipelineLogLineController : GraphQLController<LogLine> {

    @Field
    fun lineNumber(line: LogLine): Int = line.lineNumber

    @Field
    fun timestamp(line: LogLine): String = line.timestamp

    @Field
    fun content(line: LogLine): String = line.content

    @Field
    fun stream(line: LogLine): String = line.stream.name
}

@TypeController(type = "DecryptedSecret")
class DecryptedSecretController : GraphQLController<DecryptedSecret> {

    @Field
    fun name(secret: DecryptedSecret): String = secret.name

    @Field
    fun value(secret: DecryptedSecret): String = secret.value
}

@TypeController(type = "GitOrchestratorConfig")
class GitOrchestratorConfigController : GraphQLController<OrchestratorConfig> {

    @Field
    fun provider(config: OrchestratorConfig): String = config.provider

    @Field
    fun credentials(config: OrchestratorConfig): ProviderCredentials = config.credentials

    @Field
    fun defaults(config: OrchestratorConfig): VmDefaults = config.defaults

    @Field
    fun runnerProfiles(config: OrchestratorConfig): JsonElement {
        val json = kotlinx.serialization.json.Json
        return json.encodeToJsonElement(
            kotlinx.serialization.serializer<Map<String, bosca.git.model.VmProfile>>(),
            config.runnerProfiles
        )
    }

    @Field
    fun maxConcurrentVms(config: OrchestratorConfig): Int = config.maxConcurrentVms

    @Field
    fun maxJobTimeoutMinutes(config: OrchestratorConfig): Int = config.maxJobTimeoutMinutes

    @Field
    fun maxVmLifetimeMinutes(config: OrchestratorConfig): Int = config.maxVmLifetimeMinutes

    @Field
    fun alertSinks(config: OrchestratorConfig): List<AlertSink> = config.alertSinks
}

@TypeController(type = "GitProviderCredentials")
class GitProviderCredentialsController : GraphQLController<ProviderCredentials> {

    @Field
    fun selfDestructTokenScope(creds: ProviderCredentials): String? = creds.selfDestructTokenScope
}

@TypeController(type = "GitVmDefaults")
class GitVmDefaultsController : GraphQLController<VmDefaults> {

    @Field
    fun region(defaults: VmDefaults): String = defaults.region

    @Field
    fun size(defaults: VmDefaults): String = defaults.size

    @Field
    fun image(defaults: VmDefaults): String = defaults.image
}

@TypeController(type = "GitAlertSink")
class GitAlertSinkController : GraphQLController<AlertSink> {

    @Field
    fun type(sink: AlertSink): String = sink.type

    @Field
    fun url(sink: AlertSink): String = sink.url
}
