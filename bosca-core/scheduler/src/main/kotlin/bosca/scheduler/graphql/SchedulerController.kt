package bosca.scheduler.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scheduler.model.CronValidationResult
import bosca.scheduler.model.JobDefinitionInfo
import bosca.scheduler.model.JobHistory
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Scheduler

@TypeController
class SchedulerQueriesController(
    private val schedulerService: SchedulerService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Scheduler> {

    @Field
    suspend fun jobs(
        authorization: AuthenticationContext,
        enabled: Boolean?,
        limit: Int?,
        offset: Long?
    ): List<ScheduledJob> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.getJobs(enabled, limit ?: 100, offset ?: 0)
    }

    @Field
    suspend fun job(
        authorization: AuthenticationContext,
        id: UUID
    ): ScheduledJob? {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.getJob(id)
    }

    @Field
    suspend fun history(
        authorization: AuthenticationContext,
        limit: Int?,
        offset: Long?,
        status: bosca.scheduler.model.ScheduleExecutionStatus?,
        source: bosca.scheduler.model.JobHistorySource?
    ): List<JobHistory> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.getAllHistory(limit ?: 100, offset ?: 0, status, source)
    }

    @Field
    suspend fun historyCount(
        authorization: AuthenticationContext,
        status: bosca.scheduler.model.ScheduleExecutionStatus?,
        source: bosca.scheduler.model.JobHistorySource?
    ): Long {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.countAllHistory(status, source)
    }

    @Field
    suspend fun availableJobDefinitions(
        authorization: AuthenticationContext
    ): List<JobDefinitionInfo> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.getAvailableJobDefinitions()
    }

    @Field
    suspend fun validateCronExpression(
        authorization: AuthenticationContext,
        expression: String
    ): CronValidationResult {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.validateCronExpression(expression)
    }
}
