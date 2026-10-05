package bosca.git.ci.graphql

import bosca.git.model.Pipeline
import bosca.git.model.PipelineArtifact
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineArtifactService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineScheduleService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.scheduler.model.ScheduledJob
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@TypeController(type = "GitPipelineRun")
class GitPipelineRunController(
    private val jobService: PipelineJobService,
    private val artifactService: PipelineArtifactService
) : GraphQLController<PipelineRun> {

    @Field
    fun id(run: PipelineRun): UUID = run.id

    @Field
    fun pipelineId(run: PipelineRun): UUID = run.pipelineId

    @Field
    fun repositoryId(run: PipelineRun): UUID = run.repositoryId

    @Field
    fun commitSha(run: PipelineRun): String = run.commitSha

    @Field
    fun ref(run: PipelineRun): String = run.ref

    @Field
    fun triggerType(run: PipelineRun): PipelineTriggerType = run.triggerType

    @Field
    fun triggeredBy(run: PipelineRun): UUID? = run.triggeredBy

    @Field
    fun status(run: PipelineRun): PipelineRunStatus = run.status

    @Field
    fun number(run: PipelineRun): Int = run.number

    @Field
    fun concurrencyGroup(run: PipelineRun): String? = run.concurrencyGroup

    @Field
    fun parameters(run: PipelineRun): kotlinx.serialization.json.JsonElement = run.parameters

    @Field
    fun created(run: PipelineRun): OffsetDateTime = run.created

    @Field
    fun started(run: PipelineRun): OffsetDateTime? = run.started

    @Field
    fun finished(run: PipelineRun): OffsetDateTime? = run.finished

    @Field
    suspend fun jobs(run: PipelineRun): List<PipelineJob> {
        return jobService.findByRun(run.id)
    }

    @Field
    suspend fun artifacts(run: PipelineRun): List<PipelineArtifact> {
        return artifactService.listByRun(run.id)
    }

    @Field
    fun durationSeconds(run: PipelineRun): Long? {
        if (run.started == null || run.finished == null) return null
        return try {
            val started = java.time.OffsetDateTime.parse(run.started.toString())
            val finished = java.time.OffsetDateTime.parse(run.finished.toString())
            java.time.Duration.between(started, finished).seconds
        } catch (_: Exception) {
            null
        }
    }
}

@TypeController(type = "GitPipeline")
class GitPipelineController(
    private val runService: PipelineRunService,
    private val scheduleService: PipelineScheduleService,
    private val json: Json
) : GraphQLController<Pipeline> {

    @Field
    fun id(pipeline: Pipeline): UUID = pipeline.id

    @Field
    fun repositoryId(pipeline: Pipeline): UUID = pipeline.repositoryId

    @Field
    fun filePath(pipeline: Pipeline): String = pipeline.filePath

    @Field
    fun name(pipeline: Pipeline): String = pipeline.name

    @Field
    fun created(pipeline: Pipeline): OffsetDateTime = pipeline.created

    @Field
    fun updated(pipeline: Pipeline): OffsetDateTime = pipeline.updated

    @Field
    fun triggerTypes(pipeline: Pipeline): List<PipelineTriggerType> =
        json.decodeFromJsonElement(ListSerializer(PipelineTrigger.serializer()), pipeline.triggers)
            .map { it.type }
            .distinct()

    @Field
    suspend fun schedules(pipeline: Pipeline): List<ScheduledJob> =
        scheduleService.findByPipeline(pipeline.id)

    @Field
    suspend fun runs(pipeline: Pipeline, offset: Long?, limit: Int?): List<PipelineRun> {
        return runService.findByPipeline(pipeline.id, offset ?: 0, limit?.coerceIn(1, 100) ?: 25)
    }
}
