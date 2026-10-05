package bosca.scheduler.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.JobHistory
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object SchedulerMutation

@TypeController
class SchedulerMutationController(
    private val schedulerService: SchedulerService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<SchedulerMutation> {

    @Field
    suspend fun create(
        authorization: AuthenticationContext,
        input: ScheduledJobInput
    ): ScheduledJob {
        groupEvaluator.verifyHasAdminGroup(authorization)
        val principal = authorization.principal()
            ?: throw IllegalStateException("No authenticated principal")
        return schedulerService.createJob(input, principal.id)
    }

    @Field
    suspend fun edit(
        authorization: AuthenticationContext,
        id: UUID,
        input: ScheduledJobInput
    ): ScheduledJob {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.updateJob(id, input)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
    }

    @Field
    suspend fun delete(
        authorization: AuthenticationContext,
        id: UUID
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        schedulerService.deleteJob(id)
        return true
    }

    @Field
    suspend fun enable(
        authorization: AuthenticationContext,
        id: UUID
    ): ScheduledJob {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.enableJob(id)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
    }

    @Field
    suspend fun disable(
        authorization: AuthenticationContext,
        id: UUID
    ): ScheduledJob {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.disableJob(id)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
    }

    @Field
    suspend fun assignExecutionPrincipal(
        authorization: AuthenticationContext,
        id: UUID,
        principalId: UUID,
    ): ScheduledJob {
        groupEvaluator.verifyHasAdminGroup(authorization)
        val admin = authorization.principal()
            ?: throw IllegalStateException("No authenticated principal")
        return schedulerService.assignExecutionPrincipal(id, principalId, admin.id, admin.id)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
    }

    @Field
    suspend fun confirmExecutionPrincipal(
        authorization: AuthenticationContext,
        id: UUID,
    ): ScheduledJob {
        val principal = authorization.principal()
            ?: throw IllegalStateException("No authenticated principal")
        val job = schedulerService.getJob(id)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
        if (job.executionPrincipalId != principal.id && !groupEvaluator.hasAdminGroup(authorization)) {
            throw SecurityException("Only the assigned principal or an administrator may confirm this assignment")
        }
        return schedulerService.confirmExecutionPrincipal(id, principal.id)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
    }

    @Field
    suspend fun clearExecutionPrincipal(
        authorization: AuthenticationContext,
        id: UUID,
    ): ScheduledJob {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.clearExecutionPrincipal(id)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
    }

    @Field
    suspend fun trigger(
        authorization: AuthenticationContext,
        id: UUID
    ): JobHistory {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.triggerJob(id)
            ?: throw IllegalArgumentException("Scheduled job not found: $id")
    }

    @Field
    suspend fun cancel(
        authorization: AuthenticationContext,
        id: UUID
    ): JobHistory {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return schedulerService.cancelJob(id)
    }
}
