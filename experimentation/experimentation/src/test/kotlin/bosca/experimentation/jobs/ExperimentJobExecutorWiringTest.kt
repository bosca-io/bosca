@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.experimentation.jobs

import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.experimentation.configuration.JobQueueNames
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.repository.AnalysisReportRepository
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.ExperimentResultRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.repository.RolloutPolicyEventRepository
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.observability.ErrorCapture
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Exercises the handwritten executor-to-DI shims with real typed job decoding. */
class ExperimentJobExecutorWiringTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>(relaxed = true)
    private val experimentRepository = mockk<ExperimentRepository>()
    private val experimentService = mockk<ExperimentService>()
    private val flagService = mockk<FeatureFlagService>()
    private val flagRepository = mockk<FeatureFlagRepository>()
    private val eventRepository = mockk<RolloutPolicyEventRepository>()
    private val analysisReportRepository = mockk<AnalysisReportRepository>()
    private val resultRepository = mockk<ExperimentResultRepository>()
    private val assignmentRepository = mockk<AssignmentRepository>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<JobQueue>(name = JobQueueNames.experimentationJobQueue, singleton = true) { queue }
        provides<ExperimentRepository>(singleton = true) { experimentRepository }
        provides<ExperimentService>(singleton = true) { experimentService }
        provides<FeatureFlagService>(singleton = true) { flagService }
        provides<FeatureFlagRepository>(singleton = true) { flagRepository }
        provides<RolloutPolicyEventRepository>(singleton = true) { eventRepository }
        provides<AnalysisReportRepository>(singleton = true) { analysisReportRepository }
        provides<ExperimentResultRepository>(singleton = true) { resultRepository }
        provides<AssignmentRepository>(singleton = true) { assignmentRepository }
        provides<ExperimentationConfig>(singleton = true) { ExperimentationConfig() }
        provides<ErrorCapture>(singleton = true) { ErrorCapture.Noop }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { transaction<Unit>(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `rollout executor decodes job and resolves all collaborators`() = runTest {
        val experimentId = UUID.random()
        coEvery { experimentRepository.getByIdForUpdate(experimentId) } returns null
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(
                RolloutPolicyJob.serializer(),
                RolloutPolicyJob(experimentId),
            ),
            executor = RolloutPolicyJobExecutor::class,
        )

        withContext(queue.asCoroutineContext(job)) {
            RolloutPolicyJobExecutor().execute()
        }

        coVerify(exactly = 1) { experimentRepository.getByIdForUpdate(experimentId) }
    }

    @Test
    fun `analysis executor decodes job and delegates when optional analyzer is absent`() = runTest {
        val experimentId = UUID.random()
        coEvery { experimentService.getById(experimentId) } returns null
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(
                ExperimentAnalysisJob.serializer(),
                ExperimentAnalysisJob(experimentId),
            ),
            executor = ExperimentAnalysisJobExecutor::class,
        )

        withContext(queue.asCoroutineContext(job)) {
            ExperimentAnalysisJobExecutor().execute()
        }

        coVerify(exactly = 1) { experimentService.getById(experimentId) }
    }

    @Test
    fun `aggregation executor decodes job and delegates to aggregation`() = runTest {
        val experimentId = UUID.random()
        coEvery { experimentRepository.getById(experimentId) } returns null
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(
                ExperimentResultAggregationJob.serializer(),
                ExperimentResultAggregationJob(experimentId),
            ),
            executor = ExperimentResultAggregationJobExecutor::class,
        )

        withContext(queue.asCoroutineContext(job)) {
            ExperimentResultAggregationJobExecutor().execute()
        }

        coVerify(exactly = 1) { experimentRepository.getById(experimentId) }
    }

    @Test
    fun `aggregation stops when the experiment flag is missing`() = runTest {
        val experimentId = UUID.random()
        val flagId = UUID.random()
        coEvery { experimentRepository.getById(experimentId) } returns Experiment(
            id = experimentId,
            featureFlagId = flagId,
            controlVariationKey = "control",
            name = "Experiment",
        )
        coEvery { flagRepository.getById(flagId) } returns null

        aggregateExperimentResults(experimentId)

        coVerify(exactly = 1) { flagRepository.getById(flagId) }
        coVerify(exactly = 0) { experimentService.getConversionGoals(any()) }
    }

    @Test
    fun `aggregation stops when the flag has no variations or goals`() = runTest {
        val experimentId = UUID.random()
        val flagId = UUID.random()
        coEvery { experimentRepository.getById(experimentId) } returns Experiment(
            id = experimentId,
            featureFlagId = flagId,
            controlVariationKey = "control",
            name = "Experiment",
        )
        coEvery { flagRepository.getById(flagId) } returns FeatureFlag(
            id = flagId,
            key = "empty-flag",
            name = "Empty",
            variations = buildJsonArray { },
            defaultVariationKey = "control",
        )
        coEvery { experimentService.getConversionGoals(experimentId) } returns emptyList()

        aggregateExperimentResults(experimentId)

        coVerify(exactly = 1) { experimentService.getConversionGoals(experimentId) }
        coVerify(exactly = 0) { assignmentRepository.countByVariation(any(), any()) }
    }
}
