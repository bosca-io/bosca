package bosca.experimentation.graphql

import bosca.di.annotation.ProviderName
import bosca.experimentation.configuration.JobQueueNames
import bosca.experimentation.jobs.ExperimentAnalysisJob
import bosca.experimentation.jobs.ExperimentAnalysisJobExecutor
import bosca.experimentation.jobs.ExperimentResultAggregationJob
import bosca.experimentation.jobs.ExperimentResultAggregationJobExecutor
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.service.ExperimentService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue

object ExperimentsMutation

@TypeController
class ExperimentMutationController(
    private val experimentService: ExperimentService,
    private val groupEvaluator: GroupEvaluator,
    @ProviderName(JobQueueNames.experimentationJobQueue)
    private val jobQueue: JobQueue
) : GraphQLController<ExperimentsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, experiment: ExperimentInput): Experiment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.add(experiment.toServiceInput())
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, experiment: ExperimentInput): Experiment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.edit(id, experiment.toServiceInput())
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        experimentService.delete(id)
        return true
    }

    @Field
    suspend fun setStatus(authentication: AuthenticationContext, id: UUID, status: ExperimentStatus): Experiment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.setStatus(id, status)
    }

    @Field
    suspend fun addConversionGoal(
        authentication: AuthenticationContext,
        experimentId: UUID,
        goal: ConversionGoalInput,
    ): ConversionGoal {
        groupEvaluator.verifyHasAdminGroup(authentication)
        // Translate the GraphQL input (which uses GraphQLEventType for the
        // PascalCase schema enum) into the service-layer ConversionGoalInput
        // (which uses the wire-format EventType) before crossing the
        // service boundary.
        return experimentService.addConversionGoal(experimentId, goal.toServiceInput())
    }

    @Field
    suspend fun editConversionGoal(
        authentication: AuthenticationContext,
        id: UUID,
        goal: ConversionGoalInput,
    ): ConversionGoal {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.editConversionGoal(id, goal.toServiceInput())
    }

    @Field
    suspend fun deleteConversionGoal(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        experimentService.deleteConversionGoal(id)
        return true
    }

    @Field
    suspend fun aggregateResults(authentication: AuthenticationContext, experimentId: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val definition = ExperimentResultAggregationJob(experimentId)
        val job = Job(definition, ExperimentResultAggregationJobExecutor::class)
        jobQueue.enqueue(job)
        return true
    }

    @Field
    suspend fun analyze(authentication: AuthenticationContext, experimentId: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val analysisDef = ExperimentAnalysisJob(experimentId)
        val analysisJob = Job(analysisDef, ExperimentAnalysisJobExecutor::class)
        jobQueue.enqueue(analysisJob)
        return true
    }
}
