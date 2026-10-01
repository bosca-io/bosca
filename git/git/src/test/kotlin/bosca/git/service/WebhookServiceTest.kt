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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebhookServiceTest {

    private val webhookRepository = mockk<WebhookRepository>(relaxed = true)
    private val service = WebhookServiceImpl(webhookRepository)
    private val repositoryId = UUID.random()

    @Test
    fun `dispatch creates delivery for matching webhooks`() = runTest {
        val webhook = Webhook(
            id = UUID.random(),
            repositoryId = repositoryId,
            url = "https://example.com/hook",
            secret = "test-secret",
            events = listOf(WebhookEvent.PUSH)
        )
        coEvery { webhookRepository.findActiveByRepository(repositoryId) } returns listOf(webhook)
        coEvery { webhookRepository.createDelivery(any()) } answers { firstArg() }

        service.dispatch(repositoryId, WebhookEvent.PUSH, """{"ref":"refs/heads/main"}""")

        coVerify {
            webhookRepository.createDelivery(match {
                it.webhookId == webhook.id && it.event == WebhookEvent.PUSH
            })
        }
    }

    @Test
    fun `dispatch skips webhooks not subscribed to event`() = runTest {
        val webhook = Webhook(
            id = UUID.random(),
            repositoryId = repositoryId,
            url = "https://example.com/hook",
            secret = "test-secret",
            events = listOf(WebhookEvent.PUSH)
        )
        coEvery { webhookRepository.findActiveByRepository(repositoryId) } returns listOf(webhook)

        service.dispatch(repositoryId, WebhookEvent.PULL_REQUEST_OPENED, """{"action":"opened"}""")

        coVerify(exactly = 0) { webhookRepository.createDelivery(any()) }
    }

    @Test
    fun `dispatch handles multiple webhooks`() = runTest {
        val webhook1 = Webhook(
            id = UUID.random(), repositoryId = repositoryId,
            url = "https://a.com/hook", secret = "s1",
            events = listOf(WebhookEvent.PUSH, WebhookEvent.BRANCH_CREATED)
        )
        val webhook2 = Webhook(
            id = UUID.random(), repositoryId = repositoryId,
            url = "https://b.com/hook", secret = "s2",
            events = listOf(WebhookEvent.PUSH)
        )
        coEvery { webhookRepository.findActiveByRepository(repositoryId) } returns listOf(webhook1, webhook2)
        coEvery { webhookRepository.createDelivery(any()) } answers { firstArg() }

        service.dispatch(repositoryId, WebhookEvent.PUSH, """{}""")

        coVerify(exactly = 2) { webhookRepository.createDelivery(any()) }
    }

    @Test
    fun `computeSignature produces valid HMAC SHA-256`() {
        val signature = WebhookService.computeSignature("secret", "payload")
        assertTrue(signature.startsWith("sha256="))
        assertEquals(71, signature.length)
    }

    @Test
    fun `computeSignature is deterministic`() {
        val sig1 = WebhookService.computeSignature("key", "data")
        val sig2 = WebhookService.computeSignature("key", "data")
        assertEquals(sig1, sig2)
    }

    @Test
    fun `computeSignature differs for different secrets`() {
        val sig1 = WebhookService.computeSignature("key1", "data")
        val sig2 = WebhookService.computeSignature("key2", "data")
        assertTrue(sig1 != sig2)
    }

    // ── appended coverage: CRUD delegation + webhook URL validation ─────

    @Test
    fun `find and delete delegate to the repository`() = runTest {
        service.findByRepository(repositoryId)
        coVerify { webhookRepository.findByRepository(repositoryId) }

        val id = UUID.random()
        service.findById(id)
        coVerify { webhookRepository.findById(id) }

        service.delete(id)
        coVerify { webhookRepository.delete(id) }

        service.getDeliveries(id, 5, 10)
        coVerify { webhookRepository.findDeliveries(id, 5, 10) }
    }

    private fun hook(url: String) = Webhook(repositoryId = repositoryId, url = url, secret = "s")

    @Test
    fun `create accepts a public https url`() = runTest {
        coEvery { webhookRepository.create(any()) } answers { firstArg() }
        service.create(hook("https://example.com/hook"))
        coVerify { webhookRepository.create(any()) }
    }

    @Test
    fun `update validates and throws when the webhook is missing`() = runTest {
        coEvery { webhookRepository.update(any()) } returns null
        assertFailsWith<NoSuchElementException> { service.update(hook("https://example.com/h")) }
    }

    @Test
    fun `create rejects invalid, non-http, hostless, and internal urls`() = runTest {
        for (bad in listOf(
            "::not a url::",
            "ftp://example.com/x",
            "https:///nohost",
            "http://localhost/x",
            "http://127.0.0.1/x",
            "http://metadata.google.internal/x",
            "http://169.254.169.254/x",
            "http://10.0.0.5/x",       // site-local resolved address
        )) {
            assertFailsWith<IllegalArgumentException>("should reject $bad") { service.create(hook(bad)) }
        }
    }

    @Test
    fun `create rejects ipv6 loopback and wildcard hosts but accepts unresolvable ones`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.create(hook("http://[::1]/x")) }
        assertFailsWith<IllegalArgumentException> { service.create(hook("http://0.0.0.0/x")) }

        // A host that cannot resolve is allowed through: DNS may only work in prod.
        coEvery { webhookRepository.create(any()) } answers { firstArg() }
        service.create(hook("https://no-such-host.invalid/x"))
        coVerify { webhookRepository.create(match { it.url == "https://no-such-host.invalid/x" }) }
    }
}
