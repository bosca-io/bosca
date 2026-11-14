@file:OptIn(bosca.di.annotation.InternalDI::class, kotlin.uuid.ExperimentalUuidApi::class)

package bosca.recommendations.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLockFactory
import bosca.lock.DistributedLock
import bosca.recommendations.ml.TfServingConfiguration
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.service.RecommendationContextService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActivateContextModelJobExecutorTest {
    private val service = mockk<RecommendationContextService>(relaxed = true)
    private val context = RecommendationContext(id = UUID.random(), type = "test", name = "Test", selectionRevision = 3)
    private val model = RecommendationContextModel(
        version = 17, contextId = context.id, revision = 2, selectionRevision = 3,
        context = context, status = RecommendationTrainingStatus.RUNNING, exported = true, personalized = true,
    )

    @AfterTest
    fun clearRegistry() = ProviderRegistry.clear()

    private fun setup(server: MockWebServer, selected: RecommendationContext = context, snapshot: RecommendationContextModel = model,
                      contextModelUrlTemplate: String? = null) {
        server.start()
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        val locks = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        coEvery { locks.create("kubernetes-serving-model-lifecycle") } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        provides<DistributedLockFactory>(singleton = true) { locks }
        provides<RecommendationContextService>(singleton = true) { service }
        provides<TfServingConfiguration>(singleton = true) {
            TfServingConfiguration(url = server.url("/").toString(), contextModelUrlTemplate = contextModelUrlTemplate)
        }
        coEvery { service.getModel(17) } returns snapshot
        coEvery { service.getById(context.id) } returns selected
    }

    private suspend fun execute(servingEndpoint: String? = null) {
        val job = Job(definition = ActivateContextModelJob(17, 3, servingEndpoint), executor = ActivateContextModelJobExecutor::class)
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            val executor = ActivateContextModelJobExecutor()
            assertEquals("context-model-activation-17", executor.getLockId())
            executor.execute()
        }
    }

    private fun status(version: Long = 17, state: String = "AVAILABLE", code: String = "OK") = MockResponse(
        body = """{"model_version_status":[{"version":"$version","state":"$state","status":{"error_code":"$code","error_message":"load error"}}]}""",
    )

    @Test
    fun `failed completed generations only retry when still active or explicitly requested`() = runBlocking {
        val selections = listOf(
            context.copy(activeModelVersion = 17),
            context.copy(activeModelVersion = 16, requestedModelVersion = 17),
            context.copy(requestedModelVersion = 17),
            context.copy(activeModelVersion = 16, requestedModelVersion = 15),
        )
        for (selected in selections) MockWebServer().use { server ->
            setup(server, selected = selected, snapshot = model.copy(status = RecommendationTrainingStatus.COMPLETED, failure = "previous failure"))
            val retry = selected.activeModelVersion != 17L && selected.requestedModelVersion == 17L
            if (retry) { server.enqueue(status()); server.enqueue(status()) }
            execute()
            assertEquals(if (retry) 2 else 0, server.requestCount)
        }
    }

    @Test
    fun `retired completed activations and already active models stop before availability polling`() = runBlocking {
        for (selected in listOf(context.copy(selectionRevision = 4, activeModelVersion = 18), context.copy(activeModelVersion = 17), context.copy(activeModelVersion = 18))) {
            MockWebServer().use { server ->
                setup(server, selected = selected, snapshot = model.copy(status = RecommendationTrainingStatus.COMPLETED))
                repeat(2) { execute() }
                assertEquals(0, server.requestCount)
            }
        }
        coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
    }

    @Test
    fun `versioned activation waits for a claim for its own endpoint`() = runBlocking {
        MockWebServer().use { server ->
            setup(server, contextModelUrlTemplate = "http://serving-{version}.models.svc:8501")
            execute()
            execute("http://different-17.models.svc:8501")
            assertEquals(0, server.requestCount)
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
        MockWebServer().use { server ->
            setup(server, contextModelUrlTemplate = " ")
            server.enqueue(status())
            server.enqueue(status())
            execute()
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `selecting the active version clears its pending request without probing or waiting for a claim`() = runBlocking {
        MockWebServer().use { server ->
            setup(server, selected = context.copy(activeModelVersion = 17, requestedModelVersion = 17),
                snapshot = model.copy(status = RecommendationTrainingStatus.COMPLETED),
                contextModelUrlTemplate = "http://serving-{version}.models.svc:8501")
            execute()
            assertEquals(0, server.requestCount)
            coVerify(exactly = 1) { service.activateLoadedModel(17, 3) }
            coVerify(exactly = 0) { service.completeModel(any()) }
        }
    }

    @Test
    fun `reported serving failures stop superseded completed activation retries`() = runBlocking {
        MockWebServer().use { server ->
            setup(server, snapshot = model.copy(status = RecommendationTrainingStatus.COMPLETED, failure = "init failed"))
            execute()
            assertEquals(0, server.requestCount)
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
    }

    @Test
    fun `activation waits while reconciliation owns the lifecycle lock`() = runBlocking {
        MockWebServer().use { server ->
            setup(server)
            val factory = bosca.di.provide<DistributedLockFactory>()
            val busy = mockk<DistributedLock>()
            coEvery { factory.create(any()) } returns busy
            coEvery { busy.tryAcquire(any()) } returns false
            assertFailsWith<DelayException> { execute() }
            assertEquals(0, server.requestCount)
            coVerify(exactly = 0) { service.getModel(any()); service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
    }

    @Test
    fun `candidate validation uses its own endpoint before changing the active version`() = runBlocking {
        MockWebServer().use { candidate ->
            MockWebServer().use { active ->
                candidate.start()
                setup(active, contextModelUrlTemplate = candidate.url("/generation-").toString() + "{version}")
                candidate.enqueue(status())
                candidate.enqueue(MockResponse(code = 503))
                assertFailsWith<DelayException> { execute(candidate.url("/generation-17").toString()) }
                coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
                candidate.enqueue(status())
                candidate.enqueue(status())
                execute(candidate.url("/generation-17/").toString())
                assertEquals(0, active.requestCount)
                assertEquals("/generation-17/v1/models/${model.contentModelName}/versions/17",
                    candidate.takeRequest().url.encodedPath)
                coVerifyOrder { service.completeModel(17); service.activateLoadedModel(17, 3) }
            }
        }
    }

    @Test
    fun `both matching exports must load before completion and activation`() = runBlocking {
        MockWebServer().use { server ->
            setup(server)
            server.enqueue(status())
            server.enqueue(status())
            execute()
            assertEquals("/v1/models/${model.contentModelName}/versions/17", server.takeRequest().url.encodedPath)
            assertEquals("/v1/models/${model.personalizedModelName}/versions/17", server.takeRequest().url.encodedPath)
            coVerifyOrder { service.completeModel(17); service.activateLoadedModel(17, 3) }
        }
    }

    @Test
    fun `pending personalized export preserves active version`() = runBlocking {
        MockWebServer().use { server ->
            setup(server)
            server.enqueue(status())
            server.enqueue(MockResponse(code = 404))
            assertFailsWith<DelayException> { execute() }
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()); service.failServingModel(any(), any(), any()) }
        }
    }

    @Test
    fun `different version cannot satisfy load validation`() = runBlocking {
        MockWebServer().use { server ->
            setup(server)
            server.enqueue(status(version = 18))
            assertFailsWith<DelayException> { execute() }
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
    }

    @Test
    fun `available without a successful status does not activate`() = runBlocking {
        MockWebServer().use { server ->
            setup(server)
            server.enqueue(MockResponse(body = """{"model_version_status":[{"version":"17","state":"AVAILABLE"}]}"""))
            assertFailsWith<DelayException> { execute() }
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
    }

    @Test
    fun `load failure records failure without activation`() = runBlocking {
        MockWebServer().use { server ->
            setup(server)
            server.enqueue(status(state = "END", code = "INVALID_ARGUMENT"))
            assertFailsWith<FailException> { execute() }
            coVerify { service.failServingModel(17, 3, "load error") }
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
    }

    @Test
    fun `older completed job does not undo a newer selection`() = runBlocking {
        MockWebServer().use { server ->
            setup(server, selected = context.copy(selectionRevision = 4), snapshot = model.copy(status = RecommendationTrainingStatus.COMPLETED))
            server.enqueue(status())
            server.enqueue(status())
            execute()
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
    }

    @Test
    fun `content only export does not require a personalized model`() = runBlocking {
        MockWebServer().use { server ->
            setup(server, snapshot = model.copy(personalized = false))
            server.enqueue(status())
            execute()
            assertEquals(1, server.requestCount)
            coVerifyOrder { service.completeModel(17); service.activateLoadedModel(17, 3) }
        }
    }

    @Test
    fun `missing or failed models and deleted contexts make no serving requests`() = runBlocking {
        MockWebServer().use { server ->
            setup(server)
            coEvery { service.getModel(17) } returns null
            execute()
            coEvery { service.getModel(17) } returns model.copy(status = RecommendationTrainingStatus.FAILED)
            execute()
            coEvery { service.getModel(17) } returns model
            coEvery { service.getById(context.id) } returns null
            execute()
            assertEquals(0, server.requestCount)
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()) }
        }
    }

    @Test
    fun `transient HTTP failures and incomplete statuses wait without failing the version`() = runBlocking {
        MockWebServer().use { server ->
            setup(server, snapshot = model.copy(personalized = false))
            val responses = listOf(
                MockResponse(code = 503), MockResponse(code = 500), MockResponse(body = "{}"),
                MockResponse(body = """{"model_version_status":[{}]}"""),
                MockResponse(body = """{"model_version_status":[{"version":"17","status":{}}]}"""),
                MockResponse(body = """{"model_version_status":[{"version":"17","status":{"error_code":0}}]}"""),
                status(state = "LOADING"),
            )
            for (response in responses) {
                server.enqueue(response)
                assertFailsWith<DelayException> { execute() }
            }
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()); service.failServingModel(any(), any(), any()) }
            server.enqueue(status(code = "0"))
            execute()
            coVerify { service.completeModel(17); service.activateLoadedModel(17, 3) }
        }
    }

    @Test
    fun `load errors without messages and malformed responses remain observable failures`() = runBlocking {
        MockWebServer().use { server ->
            setup(server, snapshot = model.copy(personalized = false))
            server.enqueue(MockResponse(body = """{"model_version_status":[{"version":"17","status":{"error_code":"INTERNAL"}}]}"""))
            assertFailsWith<FailException> { execute() }
            coVerify { service.failServingModel(17, 3, "Model load failed: INTERNAL") }
            server.enqueue(MockResponse(body = "invalid JSON"))
            assertFailsWith<IllegalArgumentException> { execute() }
            coVerify(exactly = 2) { service.failServingModel(17, 3, any()) }
        }
    }
}
