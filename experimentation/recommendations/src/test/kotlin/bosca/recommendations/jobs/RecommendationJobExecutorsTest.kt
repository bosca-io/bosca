@file:OptIn(bosca.di.annotation.InternalDI::class, ExperimentalUuidApi::class)

package bosca.recommendations.jobs

import bosca.content.embedding.service.EmbeddingService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.model.KubernetesJobResult
import bosca.kubernetes.model.KubernetesJobResultStatus
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.service.ProfileSignalComputeService
import bosca.recommendations.service.RecommendationService
import bosca.recommendations.service.RecommendationContextService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.recommendations.configuration.JobQueueNames
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Unit tests for the recommendation job executors, driven through a minimal job harness: the executor runs
 * inside a coroutine context carrying a [Job] (built from a typed definition) and a mock [JobQueue], with
 * the executor's `provide()` dependencies registered in the [ProviderRegistry].
 */
class RecommendationJobExecutorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    // ── evaluate-strategy ────────────────────────────────────────────────────────────────────────

    @Test
    fun `evaluate-strategy executor evaluates the strategy from its definition`() = runBlocking {
        val strategyService = mockk<RecommendationStrategyService>(relaxed = true)
        val strategyId = UUID.random()
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<RecommendationStrategyService>(singleton = true) { strategyService }
        coEvery { strategyService.evaluate(strategyId) } returns mockk(relaxed = true)

        val job = Job(definition = EvaluateStrategyJob(strategyId), executor = EvaluateStrategyJobExecutor::class)
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            EvaluateStrategyJobExecutor().execute()
        }

        coVerify { strategyService.evaluate(strategyId) }
    }

    // ── remove-expired ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `remove-expired executor delegates to the service`() = runBlocking {
        val recommendationService = mockk<RecommendationService>(relaxed = true)
        ProviderRegistry.clear()
        provides<RecommendationService>(singleton = true) { recommendationService }
        coEvery { recommendationService.removeExpired() } returns 3L

        RemoveExpiredRecommendationsJobExecutor().execute()

        coVerify { recommendationService.removeExpired() }
    }

    // ── recompute-profile-signals ─────────────────────────────────────────────────────────────────

    @Test
    fun `recompute-profile-signals executor recomputes the definition's source`() = runBlocking {
        val computeService = mockk<ProfileSignalComputeService>(relaxed = true)
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<ProfileSignalComputeService>(singleton = true) { computeService }
        coEvery { computeService.recomputeForSource(any(), any()) } returns Unit

        val definition = RecomputeProfileSignalsJob(PersonalizationSignalSourceType.ATTRIBUTE, "bosca.profiles.age")
        val job = Job(definition = definition, executor = RecomputeProfileSignalsJobExecutor::class)
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            RecomputeProfileSignalsJobExecutor().execute()
        }

        coVerify { computeService.recomputeForSource(PersonalizationSignalSourceType.ATTRIBUTE, "bosca.profiles.age") }
    }

    @Test
    fun `recompute-recommendation-contexts reclassifies content then enqueues model training`() = runBlocking {
        val contextService = mockk<RecommendationContextService>(relaxed = true)
        val queue = mockk<JobQueue>(relaxed = true)
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<RecommendationContextService>(singleton = true) { contextService }
        provides<JobQueue>(name = JobQueueNames.recommendationsJobQueue, singleton = true) { queue }

        val executor = RecomputeRecommendationContextsJobExecutor()
        assertEquals("recommendation-context-recompute", executor.getLockId())
        assertFalse(executor.skipExecutionIfLocked)
        val job = Job(definition = RecomputeRecommendationContextsJob(), executor = RecomputeRecommendationContextsJobExecutor::class)
        withContext(queue.asCoroutineContext(job)) { executor.execute() }

        coVerify(exactly = 1) { contextService.recompute() }
        coVerify(exactly = 1) { queue.enqueue(any()) }
    }

    @Test
    fun `context save reclassification does not enqueue duplicate training`() = runBlocking {
        val contextService = mockk<RecommendationContextService>(relaxed = true)
        val queue = mockk<JobQueue>(relaxed = true)
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<RecommendationContextService>(singleton = true) { contextService }
        provides<JobQueue>(name = JobQueueNames.recommendationsJobQueue, singleton = true) { queue }
        val job = Job(
            definition = RecomputeRecommendationContextsJob(trainModels = false),
            executor = RecomputeRecommendationContextsJobExecutor::class,
        )
        withContext(queue.asCoroutineContext(job)) { RecomputeRecommendationContextsJobExecutor().execute() }

        coVerify(exactly = 1) { contextService.recompute() }
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `embedding backfill refreshes requested data then enqueues model training`() = runBlocking {
        val embeddingService = mockk<EmbeddingService>()
        val queue = mockk<JobQueue>(relaxed = true)
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<EmbeddingService>(singleton = true) { embeddingService }
        provides<JobQueue>(name = JobQueueNames.recommendationsJobQueue, singleton = true) { queue }
        coEvery { embeddingService.backfill(overwriteExisting = true, batchSize = 25) } returns 4L
        val definition = BackfillRecommendationEmbeddingsJob(overwriteExisting = true, batchSize = 25)
        val job = Job(definition = definition, executor = BackfillRecommendationEmbeddingsJobExecutor::class)

        val executor = BackfillRecommendationEmbeddingsJobExecutor()
        assertEquals("recommendation-embedding-backfill", executor.getLockId())
        assertFalse(executor.skipExecutionIfLocked)
        withContext(queue.asCoroutineContext(job)) { executor.execute() }

        coVerify(exactly = 1) { embeddingService.backfill(overwriteExisting = true, batchSize = 25) }
        coVerify(exactly = 1) { queue.enqueue(any()) }
    }

    @Test
    fun `embedding backfill does not retrain when no embeddings changed`() = runBlocking {
        val embeddingService = mockk<EmbeddingService>()
        val queue = mockk<JobQueue>(relaxed = true)
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<EmbeddingService>(singleton = true) { embeddingService }
        provides<JobQueue>(name = JobQueueNames.recommendationsJobQueue, singleton = true) { queue }
        coEvery { embeddingService.backfill(overwriteExisting = false, batchSize = 100) } returns 0L
        val definition = BackfillRecommendationEmbeddingsJob()
        val job = Job(definition = definition, executor = BackfillRecommendationEmbeddingsJobExecutor::class)

        withContext(queue.asCoroutineContext(job)) { BackfillRecommendationEmbeddingsJobExecutor().execute() }

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    // ── train-model ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `train-model dispatches once persists the dispatch and delays while Kubernetes is running`() = runBlocking {
        val dispatcher = mockk<KubernetesJobDispatchService>()
        val dispatchId = UUID.random()
        val request = slot<KubernetesJobRequest>()
        val persistedDefinition = slot<JsonElement>()
        val queue = mockk<JobQueue>(relaxed = true)
        val jobId = UUID.random()
        val definition = TrainModelJob(JsonObject(mapOf("epochs" to JsonPrimitive(3))), contextModelVersion = 7)
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<KubernetesJobDispatchService>(singleton = true) { dispatcher }
        val queuedJob = Job(definition = definition, executor = TrainModelJobExecutor::class)
        registerContextModel()
        queuedJob.setPersistentId(jobId)
        coEvery { dispatcher.dispatch(capture(request)) } returns dispatchId
        coEvery { dispatcher.getResult(dispatchId) } returns null

        withContext(queue.asCoroutineContext(queuedJob)) {
            assertFailsWith<DelayException> { TrainModelJobExecutor().execute() }
        }

        assertEquals("recommendation-trainer", request.captured.profile)
        assertEquals("recommendation-training-$jobId", request.captured.idempotencyKey)
        assertNull(request.captured.arguments)
        val configuration = Json.parseToJsonElement(checkNotNull(
            request.captured.environment["ML_TRAINER_CONFIGURATION_JSON"],
        )).jsonObject
        assertEquals("3", configuration["epochs"]?.jsonPrimitive?.content)
        assertEquals("7", configuration["model_version"]?.jsonPrimitive?.content)
        assertEquals("default", configuration["context"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(jobId.toString(), request.captured.labels["recommendations.bosca.io/job-id"])
        coVerify(exactly = 1) { queue.setDefinition(queuedJob, capture(persistedDefinition)) }
        val persisted = json.decodeFromJsonElement(
            TrainModelJob.serializer(),
            persistedDefinition.captured,
        )
        assertEquals(configuration, persisted.configuration)
        assertEquals(7, persisted.contextModelVersion)
        assertEquals(dispatchId, persisted.kubernetesDispatchId)
    }

    @Test
    fun `train-model resumes an existing Kubernetes dispatch and completes without redispatch`() = runBlocking {
        val dispatcher = mockk<KubernetesJobDispatchService>()
        val dispatchId = UUID.random()
        val result = kubernetesResult(dispatchId, KubernetesJobResultStatus.SUCCEEDED)
        val definition = TrainModelJob(kubernetesDispatchId = dispatchId)
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<KubernetesJobDispatchService>(singleton = true) { dispatcher }
        coEvery { dispatcher.getResult(dispatchId) } returns result

        runTrainingJob(definition) { TrainModelJobExecutor().execute() }

        coVerify(exactly = 0) { dispatcher.dispatch(any()) }
        coVerify(exactly = 1) { dispatcher.getResult(dispatchId) }
    }

    @Test
    fun `train-model preserves profile arguments when configuration is absent`() = runBlocking {
        val dispatcher = mockk<KubernetesJobDispatchService>()
        val dispatchId = UUID.random()
        val request = slot<KubernetesJobRequest>()
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<KubernetesJobDispatchService>(singleton = true) { dispatcher }
        coEvery { dispatcher.dispatch(capture(request)) } returns dispatchId
        coEvery { dispatcher.getResult(dispatchId) } returns kubernetesResult(
            dispatchId,
            KubernetesJobResultStatus.SUCCEEDED,
        )

        runTrainingJob(TrainModelJob()) { TrainModelJobExecutor().execute() }

        assertNull(request.captured.arguments)
        assertTrue(request.captured.environment.containsKey("ML_TRAINER_CONFIGURATION_JSON"))
    }

    @Test
    fun `train-model permanently fails for failed and cancelled Kubernetes workloads`() = runBlocking {
        for ((status, message) in listOf(
            KubernetesJobResultStatus.FAILED to "pod failed",
            KubernetesJobResultStatus.CANCELLED to "cancelled by operator",
        )) {
            val dispatcher = mockk<KubernetesJobDispatchService>()
            val dispatchId = UUID.random()
            ProviderRegistry.clear()
            provides<Json>(singleton = true) { json }
            provides<KubernetesJobDispatchService>(singleton = true) { dispatcher }
            coEvery { dispatcher.getResult(dispatchId) } returns kubernetesResult(dispatchId, status, message)

            val failure = assertFailsWith<FailException> {
                runTrainingJob(TrainModelJob(kubernetesDispatchId = dispatchId)) {
                    TrainModelJobExecutor().execute()
                }
            }
            assertEquals(message, failure.message)
        }
    }

    @Test
    fun `train-model supplies a fallback failure message when Kubernetes omits one`() = runBlocking {
        for ((status, expectedMessage) in listOf(
            KubernetesJobResultStatus.FAILED to "Recommendation training Kubernetes Job failed",
            KubernetesJobResultStatus.CANCELLED to "Recommendation training Kubernetes Job was cancelled",
        )) {
            val dispatcher = mockk<KubernetesJobDispatchService>()
            val dispatchId = UUID.random()
            ProviderRegistry.clear()
            provides<Json>(singleton = true) { json }
            provides<KubernetesJobDispatchService>(singleton = true) { dispatcher }
            coEvery { dispatcher.getResult(dispatchId) } returns kubernetesResult(dispatchId, status)

            val failure = assertFailsWith<FailException> {
                runTrainingJob(TrainModelJob(kubernetesDispatchId = dispatchId)) {
                    TrainModelJobExecutor().execute()
                }
            }

            assertEquals(expectedMessage, failure.message)
        }
    }

    @Test
    fun `train-model uses a per-version lock and never discards a queued context`() = runBlocking {
        val executor = TrainModelJobExecutor()
        provides<Json>(singleton = true) { json }
        runTrainingJob(TrainModelJob()) {
            assertEquals("recommendation-model-training-7", executor.getLockId())
            assertFalse(executor.skipExecutionIfLocked)
        }
    }

    private suspend fun runTrainingJob(
        definition: TrainModelJob,
        block: suspend () -> Unit,
    ) {
        registerContextModel()
        val queuedJob = Job(definition = definition.copy(contextModelVersion = 7), executor = TrainModelJobExecutor::class)
        queuedJob.setPersistentId(UUID.random())
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(queuedJob)) { block() }
    }

    private fun kubernetesResult(
        dispatchId: UUID,
        status: KubernetesJobResultStatus,
        message: String? = null,
    ) = KubernetesJobResult(
        dispatchId = dispatchId,
        profile = "recommendation-trainer",
        idempotencyKey = "recommendation-training-test",
        status = status,
        message = message,
        finishedAt = OffsetDateTime.now(),
    )

    @Test
    fun `training captures only the selected previous completed personalized model`() = runBlocking {
        for ((status, personalized) in listOf(
            RecommendationTrainingStatus.COMPLETED to true,
            RecommendationTrainingStatus.COMPLETED to false,
            RecommendationTrainingStatus.RUNNING to true,
        )) {
            ProviderRegistry.clear()
            provides<Json>(singleton = true) { json }
            val contexts = registerContextModel()
            val context = RecommendationContext(id = UUID.NIL, type = "default", name = "Default", activeModelVersion = 3)
            coEvery { contexts.getModel(7) } returns RecommendationContextModel(
                version = 7, contextId = context.id, revision = 1, selectionRevision = 1, context = context,
                status = RecommendationTrainingStatus.RUNNING, exported = true,
            )
            coEvery { contexts.getModel(3) } returns RecommendationContextModel(
                version = 3, contextId = context.id, revision = 0, selectionRevision = 0, context = context,
                status = status, personalized = personalized,
            )
            val dispatcher = mockk<KubernetesJobDispatchService>()
            provides<KubernetesJobDispatchService>(singleton = true) { dispatcher }
            val request = slot<KubernetesJobRequest>()
            val dispatchId = UUID.random()
            coEvery { dispatcher.dispatch(capture(request)) } returns dispatchId
            coEvery { dispatcher.getResult(dispatchId) } returns null
            val queued = Job(definition = TrainModelJob(JsonObject(mapOf("previous_personalized_model_version" to JsonPrimitive(999L))), contextModelVersion = 7), executor = TrainModelJobExecutor::class)
            queued.setPersistentId(UUID.random())
            withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(queued)) {
                assertFailsWith<DelayException> { TrainModelJobExecutor().execute() }
            }
            val configuration = Json.parseToJsonElement(checkNotNull(request.captured.environment["ML_TRAINER_CONFIGURATION_JSON"])).jsonObject
            assertEquals(if (status == RecommendationTrainingStatus.COMPLETED && personalized) JsonPrimitive(3L) else null,
                configuration["previous_personalized_model_version"])
        }
    }

    @Test
    fun `training handles all-context requests and skips missing or terminal versions`() = runBlocking {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        val contexts = registerContextModel()
        val allJob = Job(definition = TrainModelJob(), executor = TrainModelJobExecutor::class)
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(allJob)) {
            val executor = TrainModelJobExecutor()
            assertEquals("recommendation-model-training-all", executor.getLockId())
            executor.execute()
        }
        coVerify(exactly = 1) { contexts.trainAll() }
        for (state in listOf(null, RecommendationTrainingStatus.COMPLETED, RecommendationTrainingStatus.FAILED)) {
            coEvery { contexts.getModel(7) } returns state?.let {
                RecommendationContextModel(version = 7, contextId = UUID.NIL, revision = 1, selectionRevision = 1,
                    context = RecommendationContext(type = "default", name = "Default"), status = it)
            }
            val selectedJob = Job(definition = TrainModelJob(contextModelVersion = 7), executor = TrainModelJobExecutor::class)
            withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(selectedJob)) {
                val executor = TrainModelJobExecutor()
                assertEquals("recommendation-model-training-7", executor.getLockId())
                executor.execute()
            }
        }
        coVerify(exactly = 0) { contexts.startModel(any()) }
    }

    private fun registerContextModel(): RecommendationContextService {
        val contexts = mockk<RecommendationContextService>(relaxed = true)
        val context = RecommendationContext(id = UUID.NIL, type = "default", name = "Default")
        coEvery { contexts.getModel(7) } returns RecommendationContextModel(
            version = 7, contextId = context.id, revision = 1, selectionRevision = 1, context = context,
            status = RecommendationTrainingStatus.RUNNING, exported = true,
        )
        provides<RecommendationContextService>(singleton = true) { contexts }
        return contexts
    }

    /**
     * Runs the train-model executor against a mock trainer that predates background runs: it answers 404 to
     * the run lookup and to starting a run, then [code]/[body] to the synchronous `/train` fallback.
     */
    private fun runTrain(code: Int, body: String, configuration: JsonObject? = null) {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build())
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build())
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
        val executor = object : TrainModelJobExecutor() {
            override val httpClient = OkHttpClient()
            override val trainerUrl = server.url("/").toString().trimEnd('/')
        }
        runBlocking {
            ProviderRegistry.clear()
            provides<Json>(singleton = true) { json }
            registerContextModel()
            val job = Job(definition = TrainModelJob(configuration, contextModelVersion = 7), executor = TrainModelJobExecutor::class)
            try {
                withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) { executor.execute() }
            } finally {
                server.close()
            }
        }
        server.close()
    }

    @Test
    fun `train-model logs a completed run with content and personalized outcomes`() {
        runTrain(
            200,
            """{"status":"completed","interactions":"120","models":{
                "content":{"model_version":"7","content_items":"2000"},
                "personalized":{"status":"completed","model_version":"7","validation":{"status":"passed"},"training_users":"120","indexed_users":"140"}
            }}""",
        )
    }

    @Test
    fun `train-model logs a content upload failure and an invalid personalized artifact, and posts a config body`() {
        runTrain(
            200,
            """{"status":"completed","models":{
                "content":{"model_version":"7","content_items":"2000","bosca_upload_error":"boom"},
                "personalized":{"status":"rejected","reason":"missing serving facet"}
            }}""",
            configuration = JsonObject(mapOf("epochs" to JsonPrimitive(3))),
        )
    }

    @Test
    fun `train-model warns when the completed result is missing both model outcomes`() {
        runTrain(200, """{"status":"completed","models":{}}""")
    }

    @Test
    fun `train-model logs a skipped personalized model`() {
        runTrain(
            200,
            """{"status":"completed","models":{"content":{"model_version":"7"},"personalized":{"status":"skipped","reason":"too few interactions"}}}""",
        )
    }

    @Test
    fun `train-model warns on an unexpected personalized status`() {
        runTrain(
            200,
            """{"status":"completed","models":{"content":{"model_version":"7"},"personalized":{"status":"weird"}}}""",
        )
    }

    @Test
    fun `train-model handles the no_content, already_training and unknown statuses`() {
        runTrain(200, """{"status":"no_content","message":"no items"}""")
        runTrain(200, """{"status":"no_content","message":{}}""")
        runTrain(200, """{"status":"no_content"}""")
        assertFailsWith<DelayException> { runTrain(200, """{"status":"already_training"}""") }
        runTrain(200, """{"status":"bogus"}""")
        runTrain(200, """{"status":{}}""")
        runTrain(200, """{}""") // missing status → "unknown" → else branch
    }

    @Test
    fun `train-model throws on a non-2xx trainer response`() {
        assertFailsWith<RuntimeException> { runTrain(500, "internal error") }
    }

    @Test
    fun `train-model handles a completed run with no interactions or model outcomes`() {
        runTrain(200, """{"status":"completed"}""") // interactions + models absent → both outcomes warn
    }

    @Test
    fun `train-model handles model blocks missing their optional fields`() {
        // content without a version/items; personalized completed without optional validation details.
        runTrain(200, """{"status":"completed","models":{"content":{},"personalized":{"status":"completed"}}}""")
    }

    @Test
    fun `completed trainer responses tolerate optional validation and skip reason shapes`() {
        for (validation in listOf("null", "{}", "1", "{\"status\":{}}", "{\"status\":\"passed\"}")) {
            runTrain(200, """{"status":"completed","models":{"content":{"validation":$validation},"personalized":{"status":"skipped"}}}""")
        }
        runTrain(200, """{"status":"completed","models":{"personalized":{"status":"skipped","reason":{}}}}""")
    }

    /** Runs one train-model execution against a trainer returning [responses] in order; returns its requests. */
    private fun runTrainerRun(
        vararg responses: Pair<Int, String>,
        expectFailure: kotlin.reflect.KClass<out Throwable>? = null,
    ): List<mockwebserver3.RecordedRequest> {
        val server = MockWebServer()
        server.start()
        responses.forEach { (code, body) -> server.enqueue(MockResponse.Builder().code(code).body(body).build()) }
        val executor = object : TrainModelJobExecutor() {
            override val httpClient = OkHttpClient()
            override val trainerUrl = server.url("/").toString().trimEnd('/')
        }
        try {
            runBlocking {
                ProviderRegistry.clear()
                provides<Json>(singleton = true) { json }
                registerContextModel()
                val definition = TrainModelJob(JsonObject(mapOf("epochs" to JsonPrimitive(3))), contextModelVersion = 7)
                val job = Job(definition = definition, executor = TrainModelJobExecutor::class)
                withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
                    if (expectFailure == null) {
                        executor.execute()
                    } else {
                        val thrown = runCatching { executor.execute() }.exceptionOrNull()
                        assertNotNull(thrown, "expected ${expectFailure.simpleName}")
                        assertTrue(expectFailure.isInstance(thrown), "expected ${expectFailure.simpleName} but was $thrown")
                    }
                }
            }
            return List(server.requestCount) { server.takeRequest() }
        } finally {
            server.close()
        }
    }

    @Test
    fun `train-model starts the version's trainer run in the background and polls later`() {
        val requests = runTrainerRun(
            404 to """{"error":"Unknown run"}""",
            202 to """{"status":"started","run_id":"recommendation-model-7"}""",
            expectFailure = DelayException::class,
        )

        assertEquals(listOf("GET", "POST"), requests.map { it.method })
        assertEquals(listOf("/runs/recommendation-model-7", "/runs/recommendation-model-7"), requests.map { it.target })
        val body = json.parseToJsonElement(requests[1].body?.utf8() ?: "").jsonObject
        assertEquals("3", body["epochs"]?.jsonPrimitive?.content)
        assertEquals("7", body["model_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `train-model keeps polling while the trainer run is in progress`() {
        val requests = runTrainerRun(
            200 to """{"status":"running","run_id":"recommendation-model-7"}""",
            expectFailure = DelayException::class,
        )
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test
    fun `train-model completes from a finished trainer run without starting another`() {
        val requests = runTrainerRun(
            200 to """{"status":"finished","run_id":"recommendation-model-7","result":{"status":"completed",
                "models":{"content":{"model_version":"7"},"personalized":{"status":"skipped","reason":"few"}}}}""",
        )
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test
    fun `train-model waits when another run holds the trainer or a start races`() {
        for (code in listOf(200, 409)) {
            runTrainerRun(
                404 to """{"error":"Unknown run"}""",
                code to """{"status":"already_training","run_id":"recommendation-model-6"}""",
                expectFailure = DelayException::class,
            )
        }
    }

    @Test
    fun `train-model fails the version on trainer run errors`() {
        runTrainerRun(404 to "{}", 500 to "boom", expectFailure = RuntimeException::class)
        runTrainerRun(500 to "boom", expectFailure = RuntimeException::class)
        runTrainerRun(200 to """{"status":"mystery"}""", expectFailure = RuntimeException::class)
        runTrainerRun(200 to """{"status":"finished","run_id":"recommendation-model-7"}""", expectFailure = FailException::class)
    }

    @Test
    fun `the default trainer url and http client are available when not overridden`() {
        val executor = object : TrainModelJobExecutor() {
            fun defaultUrl() = trainerUrl
            fun defaultClient() = httpClient
        }
        assertTrue(executor.defaultUrl().isNotEmpty())
        assertNotNull(executor.defaultClient())
    }
}
