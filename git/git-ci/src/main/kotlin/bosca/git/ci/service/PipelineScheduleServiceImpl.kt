package bosca.git.ci.service

import bosca.git.model.Pipeline
import bosca.git.model.PipelineScheduleJob
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineScheduleService
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json

/** Directly mirrors Git cron triggers into principal-aware platform scheduler jobs. */
@ServiceImplementation
class PipelineScheduleServiceImpl(
    private val schedulerService: SchedulerService,
    private val json: Json,
) : PipelineScheduleService {

    override suspend fun sync(pipeline: Pipeline, triggers: List<PipelineTrigger>): List<ScheduledJob> {
        val crons = triggers.asSequence()
            .filter { it.type == PipelineTriggerType.SCHEDULE }
            .mapNotNull { it.cron?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
        val existing = findByPipeline(pipeline.id)
        val desired = crons.toSet()

        val retained = mutableMapOf<String, ScheduledJob>()
        for (job in existing) {
            if (job.cronExpression !in desired || retained.putIfAbsent(job.cronExpression, job) != null) {
                schedulerService.deleteJob(job.id)
            }
        }
        for (cron in crons) {
            val current = retained[cron]
            val input = schedulerInput(pipeline, cron)
            retained[cron] = when {
                current == null -> schedulerService.createJob(input, UUID.NIL)
                current.matches(input) -> current
                else -> schedulerService.updateJob(current.id, input)
                    ?: throw NoSuchElementException("Scheduled job not found: ${current.id}")
            }
        }
        return crons.mapNotNull(retained::get)
    }

    override suspend fun findById(id: UUID): ScheduledJob? {
        val job = schedulerService.getJob(id) ?: return null
        return if (job.jobName == PipelineScheduleJob.NAME) job else null
    }

    override suspend fun findByPipeline(pipelineId: UUID): List<ScheduledJob> =
        schedulerService.getJobsByName(PipelineScheduleJob.NAME, SCHEDULE_SCAN_LIMIT)
            .filter { decodePipelineId(it) == pipelineId }

    override suspend fun delete(id: UUID) {
        findById(id)?.let { schedulerService.deleteJob(it.id) }
    }

    override suspend fun deleteByPipeline(pipelineId: UUID) {
        findByPipeline(pipelineId).forEach { schedulerService.deleteJob(it.id) }
    }

    private fun schedulerInput(pipeline: Pipeline, cron: String) = ScheduledJobInput(
        name = "Git pipeline schedule: ${pipeline.name}",
        description = "Runs ${pipeline.filePath} as its explicitly assigned principal.",
        jobName = PipelineScheduleJob.NAME,
        jobParameters = json.encodeToJsonElement(
            PipelineScheduleJob.serializer(),
            PipelineScheduleJob(pipeline.id),
        ),
        cronExpression = cron,
        enabled = true,
        allowConcurrent = false,
        catchUp = false,
        maxCatchUp = 1,
        requiresPrincipal = true,
    )

    private fun decodePipelineId(job: ScheduledJob): UUID? =
        runCatching { json.decodeFromJsonElement(PipelineScheduleJob.serializer(), job.jobParameters).pipelineId }
            .getOrNull()

    private fun ScheduledJob.matches(input: ScheduledJobInput): Boolean =
        name == input.name &&
            description == input.description &&
            allowConcurrent == input.allowConcurrent &&
            catchUp == input.catchUp &&
            maxCatchUp == input.maxCatchUp &&
            principalState != bosca.scheduler.model.ScheduledJobPrincipalState.NOT_REQUIRED

    companion object {
        private const val SCHEDULE_SCAN_LIMIT = 10_000
    }
}
