package bosca.git.service

import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.git.model.WebhookEvent
import bosca.git.repository.WebhookRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class WebhookDeliveryTest {

    private val webhookRepository = mockk<WebhookRepository>(relaxed = true)
    private lateinit var service: WebhookService

    private val repositoryId = UUID.random()
    private val webhookId = UUID.random()

    @BeforeTest
    fun setup() {
        service = WebhookServiceImpl(webhookRepository)
    }

    @Test
    fun `dispatch creates delivery records for matching webhooks`() = runTest {
        val webhook = Webhook(
            id = webhookId,
            repositoryId = repositoryId,
            url = "https://example.com/webhook",
            secret = "test-secret",
            events = listOf(WebhookEvent.PUSH, WebhookEvent.PULL_REQUEST_MERGED),
            active = true
        )
        coEvery { webhookRepository.findActiveByRepository(repositoryId) } returns listOf(webhook)
        coEvery { webhookRepository.createDelivery(any()) } answers {
            firstArg<WebhookDelivery>().copy(id = UUID.random())
        }

        service.dispatch(repositoryId, WebhookEvent.PUSH, "{\"ref\":\"refs/heads/main\"}")

        coVerify { webhookRepository.createDelivery(match { it.webhookId == webhookId && it.event == WebhookEvent.PUSH }) }
    }

    @Test
    fun `dispatch skips webhooks not subscribed to event`() = runTest {
        val pushOnlyWebhook = Webhook(
            id = webhookId,
            repositoryId = repositoryId,
            url = "https://example.com/webhook",
            secret = "test-secret",
            events = listOf(WebhookEvent.PUSH),
            active = true
        )
        coEvery { webhookRepository.findActiveByRepository(repositoryId) } returns listOf(pushOnlyWebhook)

        service.dispatch(repositoryId, WebhookEvent.PULL_REQUEST_MERGED, "{}")

        coVerify(exactly = 0) { webhookRepository.createDelivery(any()) }
    }

    @Test
    fun `HMAC signature is computed correctly`() {
        val signature = WebhookService.computeSignature("secret", "payload")
        assertTrue(signature.startsWith("sha256="))
        assertTrue(signature.length > 10)
    }

    @Test
    fun `HMAC signature is deterministic`() {
        val sig1 = WebhookService.computeSignature("secret", "payload")
        val sig2 = WebhookService.computeSignature("secret", "payload")
        assertTrue(sig1 == sig2)
    }

    @Test
    fun `HMAC signature changes with different secrets`() {
        val sig1 = WebhookService.computeSignature("secret1", "payload")
        val sig2 = WebhookService.computeSignature("secret2", "payload")
        assertTrue(sig1 != sig2)
    }
}
