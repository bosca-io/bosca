package bosca.git.ci.graphql

import bosca.git.model.PipelineAgent
import bosca.git.model.ArtifactDefinition
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineStep
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@TypeController(type = "GitPipelineJob")
class GitPipelineJobController(
    private val jobService: PipelineJobService,
    private val agentService: PipelineAgentService,
) : GraphQLController<PipelineJob> {

    @Field
    fun id(job: PipelineJob): UUID = job.id

    @Field
    fun pipelineRunId(job: PipelineJob): UUID = job.pipelineRunId

    @Field
    fun name(job: PipelineJob): String = job.name

    @Field
    fun status(job: PipelineJob): PipelineRunStatus = job.status

    @Field
    fun runnerLabel(job: PipelineJob): String = job.runnerLabel

    @Field
    fun agentId(job: PipelineJob): UUID? = job.agentId

    @Field
    fun matrixValues(job: PipelineJob): JsonElement = job.matrixValues

    @Field
    fun artifacts(job: PipelineJob): List<ArtifactDefinition> = job.artifacts

    @Field
    fun dependsOn(job: PipelineJob): List<String> = job.dependsOn

    @Field
    fun timeoutMinutes(job: PipelineJob): Int? = job.timeoutMinutes

    @Field
    fun requirements(job: PipelineJob): JsonElement = job.requirements

    @Field
    fun pipelineRequirements(job: PipelineJob): JsonElement = job.pipelineRequirements

    @Field
    fun requirementsSatisfiedAt(job: PipelineJob): OffsetDateTime? = job.requirementsSatisfiedAt

    @Field
    fun requirementsBypassedAt(job: PipelineJob): OffsetDateTime? = job.requirementsBypassedAt

    @Field
    fun requirementsBypassedBy(job: PipelineJob): UUID? = job.requirementsBypassedBy

    @Field
    fun requirementsBypassReason(job: PipelineJob): String? = job.requirementsBypassReason

    @Field
    fun requirementsDeadline(job: PipelineJob): OffsetDateTime? = job.requirementsDeadline

    /** The "waiting on requirements" state: queued, gated, and not yet verified. */
    @Field
    fun awaitingRequirements(job: PipelineJob): Boolean =
        job.status == PipelineRunStatus.QUEUED &&
            job.requirementsSatisfiedAt == null &&
            ((job.requirements as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true ||
                (job.pipelineRequirements as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true)

    @Field
    fun errorMessage(job: PipelineJob): String? = job.errorMessage

    @Field
    fun attempt(job: PipelineJob): Int = job.attempt

    @Field
    fun condition(job: PipelineJob): String? = job.condition

    @Field
    fun conditionSatisfiedAt(job: PipelineJob): OffsetDateTime? = job.conditionSatisfiedAt

    @Field
    fun environment(job: PipelineJob): String? = job.environment

    @Field
    fun secretNames(job: PipelineJob): List<String> = job.secretNames

    @Field
    fun approvalRequired(job: PipelineJob): Boolean = job.approvalRequired

    @Field
    fun approvedAt(job: PipelineJob): OffsetDateTime? = job.approvedAt

    @Field
    fun approvedBy(job: PipelineJob): UUID? = job.approvedBy

    @Field
    fun approvalComment(job: PipelineJob): String? = job.approvalComment

    /** Parked at the approval gate: queued, unapproved, every earlier gate cleared. */
    @Field
    suspend fun awaitingApproval(job: PipelineJob): Boolean = jobService.isAwaitingApproval(job)

    @Field
    fun started(job: PipelineJob): OffsetDateTime? = job.started

    @Field
    fun finished(job: PipelineJob): OffsetDateTime? = job.finished

    @Field
    suspend fun agent(job: PipelineJob): PipelineAgent? {
        val id = job.agentId ?: return null
        return agentService.findById(id)
    }

    @Field
    suspend fun steps(job: PipelineJob): List<PipelineStep> {
        return jobService.getSteps(job.id)
    }
}
