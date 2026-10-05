@file:OptIn(bosca.di.annotation.InternalDI::class, kotlin.uuid.ExperimentalUuidApi::class)

package bosca.recommendations.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
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

    private fun setup(server: MockWebServer, selected: RecommendationContext = context, snapshot: RecommendationContextModel = model) {
        server.start()
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        provides<RecommendationContextService>(singleton = true) { service }
        provides<TfServingConfiguration>(singleton = true) { TfServingConfiguration(url = server.url("/").toString()) }
        coEvery { service.getModel(17) } returns snapshot
        coEvery { service.getById(context.id) } returns selected
    }

    private suspend fun execute() {
        val job = Job(definition = ActivateContextModelJob(17, 3), executor = ActivateContextModelJobExecutor::class)
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
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()); service.failModel(any(), any()) }
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
            coVerify { service.failModel(17, "load error") }
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
            coVerify(exactly = 0) { service.completeModel(any()); service.activateLoadedModel(any(), any()); service.failModel(any(), any()) }
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
            coVerify { service.failModel(17, "Model load failed: INTERNAL") }
            server.enqueue(MockResponse(body = "invalid JSON"))
            assertFailsWith<IllegalArgumentException> { execute() }
            coVerify(exactly = 2) { service.failModel(17, any()) }
        }
    }
}
