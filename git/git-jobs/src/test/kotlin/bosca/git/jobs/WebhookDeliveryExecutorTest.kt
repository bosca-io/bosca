@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.git.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.git.model.WebhookEvent
import bosca.git.repository.WebhookRepository
import bosca.git.service.WebhookService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Branch coverage for [WebhookDeliveryExecutor]: missing delivery/webhook short
 * circuits, a successful HTTP delivery, and a failed (non-2xx) delivery that
 * records the result then throws so the queue retries. Uses a real local HTTP
 * server so the OkHttp path is exercised end to end.
 */
class WebhookDeliveryExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val webhookRepository = mockk<WebhookRepository>(relaxed = true)
    private val webhookService = mockk<WebhookService>(relaxed = true)
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<WebhookRepository>(singleton = true) { webhookRepository }
        provides<WebhookService>(singleton = true) { webhookService }
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() {
        server.close()
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun delivery(webhookId: UUID) = WebhookDelivery(
        id = UUID.random(), webhookId = webhookId, event = WebhookEvent.PUSH, payload = """{"x":1}""",
    )

    private fun webhook() = Webhook(
        id = UUID.random(), repositoryId = UUID.random(),
        url = server.url("/hook").toString(), secret = "s3cr3t",
    )

    @Test
    fun `returns when the delivery is missing`() = runTest {
        val id = UUID.random()
        coEvery { webhookRepository.findDeliveryById(id) } returns null

        WebhookDeliveryExecutor.deliver(WebhookDeliveryJob(id), webhookRepository, webhookService)

        coVerify(exactly = 0) { webhookRepository.recordDeliveryResult(any(), any(), any()) }
    }

    @Test
    fun `returns when the webhook is missing`() = runTest {
        val d = delivery(UUID.random())
        coEvery { webhookRepository.findDeliveryById(d.id) } returns d
        coEvery { webhookRepository.findById(d.webhookId) } returns null

        WebhookDeliveryExecutor.deliver(WebhookDeliveryJob(d.id), webhookRepository, webhookService)

        coVerify(exactly = 0) { webhookRepository.recordDeliveryResult(any(), any(), any()) }
    }

    @Test
    fun `records a successful delivery`() = runTest {
        val w = webhook()
        val d = delivery(w.id)
        coEvery { webhookRepository.findDeliveryById(d.id) } returns d
        coEvery { webhookRepository.findById(w.id) } returns w
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        WebhookDeliveryExecutor.deliver(WebhookDeliveryJob(d.id), webhookRepository, webhookService)

        coVerify { webhookRepository.recordDeliveryResult(d.id, 200, any()) }
    }

    @Test
    fun `records then throws on a failed delivery`() = runTest {
        val w = webhook()
        val d = delivery(w.id)
        coEvery { webhookRepository.findDeliveryById(d.id) } returns d
        coEvery { webhookRepository.findById(w.id) } returns w
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())

        assertFailsWith<RuntimeException> {
            WebhookDeliveryExecutor.deliver(WebhookDeliveryJob(d.id), webhookRepository, webhookService)
        }

        coVerify { webhookRepository.recordDeliveryResult(d.id, 500, any()) }
    }

    @Test
    fun `execute drives delivery from the job definition`() = runTest {
        val w = webhook()
        val d = delivery(w.id)
        coEvery { webhookRepository.findDeliveryById(d.id) } returns d
        coEvery { webhookRepository.findById(w.id) } returns w
        server.enqueue(MockResponse.Builder().code(204).build())

        val jobObj: Job = InternalJobConstructor(
            definition = json.encodeToJsonElement(WebhookDeliveryJob.serializer(), WebhookDeliveryJob(d.id)),
            executor = WebhookDeliveryExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObj)) { WebhookDeliveryExecutor().execute() }

        coVerify { webhookRepository.recordDeliveryResult(d.id, 204, any()) }
    }
}
