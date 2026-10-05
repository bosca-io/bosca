package bosca.scheduler.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.JobHistory
import bosca.scheduler.service.SchedulerService

@TypeController
class ScheduledJobController(
    private val schedulerService: SchedulerService
) : GraphQLController<ScheduledJob> {

    @Field
    fun id(job: ScheduledJob) = job.id

    @Field
    fun name(job: ScheduledJob) = job.name

    @Field
    fun description(job: ScheduledJob) = job.description

    @Field
    fun jobName(job: ScheduledJob) = job.jobName

    @Field
    fun jobParameters(job: ScheduledJob) = job.jobParameters

    @Field
    fun cronExpression(job: ScheduledJob) = job.cronExpression

    @Field
    fun enabled(job: ScheduledJob) = job.enabled

    @Field
    fun allowConcurrent(job: ScheduledJob) = job.allowConcurrent

    @Field
    fun catchUp(job: ScheduledJob) = job.catchUp

    @Field
    fun maxCatchUp(job: ScheduledJob) = job.maxCatchUp

    @Field
    fun createdAt(job: ScheduledJob) = job.createdAt

    @Field
    fun updatedAt(job: ScheduledJob) = job.updatedAt

    @Field
    fun createdBy(job: ScheduledJob) = job.createdBy

    @Field
    fun executionPrincipalId(job: ScheduledJob) = job.executionPrincipalId

    @Field
    fun principalState(job: ScheduledJob) = job.principalState

    @Field
    fun principalAssignedBy(job: ScheduledJob) = job.principalAssignedBy

    @Field
    fun principalConfirmedBy(job: ScheduledJob) = job.principalConfirmedBy

    @Field
    fun lastRunAt(job: ScheduledJob) = job.lastRunAt

    @Field
    fun nextRunAt(job: ScheduledJob) = job.nextRunAt

    @Field
    suspend fun history(
        scheduledJob: ScheduledJob,
        limit: Int?,
        offset: Long?,
        status: bosca.scheduler.model.ScheduleExecutionStatus?,
        source: bosca.scheduler.model.JobHistorySource?
    ): List<JobHistory> {
        return schedulerService.getHistory(scheduledJob.id, limit ?: 100, offset ?: 0, status, source)
    }

    @Field
    suspend fun historyCount(
        scheduledJob: ScheduledJob,
        status: bosca.scheduler.model.ScheduleExecutionStatus?,
        source: bosca.scheduler.model.JobHistorySource?
    ): Long {
        return schedulerService.countHistory(scheduledJob.id, status, source)
    }
}
