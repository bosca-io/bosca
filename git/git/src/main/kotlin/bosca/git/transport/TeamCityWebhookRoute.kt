package bosca.git.transport

import bosca.git.model.CommitStatusState
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.CommitStatusService
import bosca.git.service.RepositoryService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Receives TeamCity build event webhooks and translates them to commit status
 * updates. TeamCity sends JSON payloads containing build status, commit SHA,
 * and project details which are mapped to [CommitStatusState] values.
 */
@RouteController(
    path = "/api/webhooks/teamcity",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED
)
class TeamCityWebhookRoute(
    private val commitStatusService: CommitStatusService,
    private val repositoryService: RepositoryService,
    private val permissionEvaluator: RepositoryPermissionEvaluator
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val body = call.request.bodyText()
        val payload = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            log.warn("Failed to parse TeamCity webhook payload: {}", e.message)
            return call.respond(HttpStatusCode.BadRequest)
        }

        val build = payload["build"]?.jsonObject ?: run {
            log.warn("TeamCity webhook missing 'build' field")
            return call.respond(HttpStatusCode.BadRequest)
        }

        val buildStatus = build["status"]?.jsonPrimitive?.content
        val buildState = build["state"]?.jsonPrimitive?.content
        val buildTypeId = build["buildTypeId"]?.jsonPrimitive?.content ?: "unknown"
        val statusText = build["statusText"]?.jsonPrimitive?.content
        val webUrl = build["webUrl"]?.jsonPrimitive?.content

        val revisions = build["revisions"]?.jsonObject?.get("revision")
        val commitSha = when {
            revisions is kotlinx.serialization.json.JsonArray && revisions.isNotEmpty() ->
                revisions[0].jsonObject["version"]?.jsonPrimitive?.content
            else -> null
        }

        if (commitSha == null) {
            log.debug("TeamCity webhook has no revision, skipping status update")
            return call.respond(HttpStatusCode.OK)
        }

        val repositoryIdStr = call.request.queryParameters["repositoryId"]
        val repositoryId = if (repositoryIdStr != null) {
            try { UUID.parse(repositoryIdStr) } catch (_: Exception) {
                return call.respond(HttpStatusCode.BadRequest)
            }
        } else {
            log.warn("TeamCity webhook missing repositoryId query parameter")
            return call.respond(HttpStatusCode.BadRequest)
        }

        val repository = repositoryService.findById(repositoryId)
            ?: return call.respond(HttpStatusCode.NotFound)
        permissionEvaluator.verifyAllowed(authenticationContext, repository, PermissionAction.EDIT)

        val state = translateBuildState(buildState, buildStatus)
        val context = "teamcity/$buildTypeId"

        commitStatusService.recordStatus(
            repositoryId = repositoryId,
            commitSha = commitSha,
            context = context,
            state = state,
            description = statusText,
            targetUrl = webUrl
        )

        log.info("TeamCity webhook: {} → {} for commit {} on repo {}", buildTypeId, state, commitSha.take(8), repositoryId)
        call.respond(HttpStatusCode.OK)
    }

    companion object {
        private val log = LoggerFactory.getLogger(TeamCityWebhookRoute::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        internal fun translateBuildState(state: String?, status: String?): CommitStatusState {
            return when {
                state == "running" -> CommitStatusState.PENDING
                state == "queued" -> CommitStatusState.PENDING
                status == "SUCCESS" -> CommitStatusState.SUCCESS
                status == "FAILURE" -> CommitStatusState.FAILURE
                status == "ERROR" -> CommitStatusState.ERROR
                state == "finished" && status == null -> CommitStatusState.SUCCESS
                else -> CommitStatusState.PENDING
            }
        }
    }
}
