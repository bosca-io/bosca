package bosca.git.transport

import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubDeliveryConflictException
import bosca.git.model.GitHubWebhookRejectedException
import bosca.git.model.GitHubWebhookInputException
import bosca.git.model.GitHubWebhookUnavailableException
import bosca.git.service.GitHubSyncService
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class GitHubWebhookRouteTest {
    private val service = mockk<GitHubSyncService>()
    private val route = GitHubWebhookRoute(service)
    private val repositoryId = UUID.random()
    private val id = UUID.random().toString()
    private val headers = mapOf("X-GitHub-Delivery" to id, "X-GitHub-Event" to "push", "X-Hub-Signature-256" to "signature")

    @BeforeTest fun setup() { clearRouteProviders(); registerRouteProviders() }
    @AfterTest fun cleanup() = clearRouteProviders()

    @Test fun `anonymous intake forwards exact raw bytes and acknowledges after persistence`() = runTest {
        val body = "{\n \"text\":\"café ✓\"\n}".toByteArray()
        coEvery { service.onDelivery(repositoryId, id, "push", "signature", any()) } returns
            GitHubDelivery(id, repositoryId, "push", JsonNull, "digest")
        val (call, response) = recordedCall(pathParameters = mapOf("repositoryId" to repositoryId.toString()), headers = headers, body = body)
        runRoute(route, call)
        assertEquals(HttpStatusCode.Accepted, response.status)
        coVerify(exactly = 1) { service.onDelivery(repositoryId, id, "push", "signature", match { it.contentEquals(body) }) }
    }

    @Test fun `missing routing and headers are rejected without invoking intake`() = runTest {
        for (path in listOf(emptyMap(), mapOf("repositoryId" to "invalid"))) {
            val (call, response) = recordedCall(pathParameters = path, headers = headers)
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        for (missing in listOf("X-GitHub-Delivery", "X-GitHub-Event")) {
            val (call, response) = recordedCall(pathParameters = mapOf("repositoryId" to repositoryId.toString()), headers = headers - missing)
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        coVerify(exactly = 0) { service.onDelivery(any(), any(), any(), any(), any()) }
    }

    @Test fun `expected client failures have distinct status codes`() = runTest {
        for ((exception, expected) in listOf(
            GitHubWebhookRejectedException() to HttpStatusCode.Forbidden,
            GitHubDeliveryConflictException() to HttpStatusCode.Conflict,
            GitHubWebhookInputException("bad JSON") to HttpStatusCode.BadRequest,
            GitHubWebhookInputException("bad delivery header") to HttpStatusCode.BadRequest,
            GitHubWebhookUnavailableException() to HttpStatusCode.ServiceUnavailable,
        )) {
            coEvery { service.onDelivery(any(), any(), any(), any(), any()) } throws exception
            val (call, response) = recordedCall(pathParameters = mapOf("repositoryId" to repositoryId.toString()), headers = headers)
            runRoute(route, call)
            assertEquals(expected, response.status)
        }
    }

    @Test fun `missing signature reaches verification and cannot use session authentication`() = runTest {
        coEvery { service.onDelivery(repositoryId, id, "push", null, any()) } throws GitHubWebhookRejectedException()
        val (call, response) = recordedCall(pathParameters = mapOf("repositoryId" to repositoryId.toString()), headers = headers - "X-Hub-Signature-256")
        runRoute(route, call)
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test fun `infrastructure failures produce server errors without acknowledging delivery`() = runTest {
        for (failure in listOf(
            RuntimeException("database down"), SerializationException("invalid stored graph"),
            IllegalArgumentException("invalid pipeline configuration"), IllegalStateException("queue unavailable"),
        )) {
            coEvery { service.onDelivery(any(), any(), any(), any(), any()) } throws failure
            val (call, response) = recordedCall(pathParameters = mapOf("repositoryId" to repositoryId.toString()), headers = headers)
            runRoute(route, call)
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }
    }

    @Test fun `cancellation propagates without acknowledging delivery`() = runTest {
        val exception = CancellationException("cancelled")
        coEvery { service.onDelivery(any(), any(), any(), any(), any()) } throws exception
        val (call, response) = recordedCall(pathParameters = mapOf("repositoryId" to repositoryId.toString()), headers = headers)
        assertEquals("cancelled", assertFailsWith<CancellationException> { runRoute(route, call) }.message)
        assertNull(response.status)
    }
}
