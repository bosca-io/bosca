package bosca.git.transport

import bosca.git.model.GitHubDeliveryConflictException
import bosca.git.model.GitHubWebhookRejectedException
import bosca.git.model.GitHubWebhookInputException
import bosca.git.model.GitHubWebhookUnavailableException
import bosca.git.service.GitHubSyncService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.CancellationException

/** GitHub authenticates through the raw body's HMAC, independently of Bosca session authentication. */
@RouteController("/api/webhooks/github/{repositoryId}", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class GitHubWebhookRoute(private val service: GitHubSyncService) : Route<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val repositoryId = call.pathParameters["repositoryId"]?.let {
            try { UUID.parse(it) } catch (_: IllegalArgumentException) { null }
        } ?: return call.respond(HttpStatusCode.BadRequest)
        val deliveryId = call.request.headers["X-GitHub-Delivery"] ?: return call.respond(HttpStatusCode.BadRequest)
        val event = call.request.headers["X-GitHub-Event"] ?: return call.respond(HttpStatusCode.BadRequest)
        val body = call.request.bodyBytes()
        try {
            service.onDelivery(repositoryId, deliveryId, event, call.request.headers["X-Hub-Signature-256"], body)
        } catch (_: GitHubWebhookRejectedException) {
            return call.respond(HttpStatusCode.Forbidden)
        } catch (_: GitHubDeliveryConflictException) {
            return call.respond(HttpStatusCode.Conflict)
        } catch (_: GitHubWebhookInputException) {
            return call.respond(HttpStatusCode.BadRequest)
        } catch (_: GitHubWebhookUnavailableException) {
            return call.respond(HttpStatusCode.ServiceUnavailable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("GitHub delivery processing failed", e)
        }
        call.respond(HttpStatusCode.Accepted)
    }
}
