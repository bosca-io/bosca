package bosca.artifacts.npm.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Searches for npm packages by name. Implements the `/-/v1/search` endpoint
 * that npm/pnpm clients call during `npm search`.
 *
 * Results are filtered to only include packages the caller has pull access to.
 * Public namespace packages are visible to unauthenticated callers.
 */
@RouteController("/npm/-/v1/search", authentication = RouteAuthentication.OPTIONAL)
class NpmSearch(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {

    private val log = LoggerFactory.getLogger(NpmSearch::class.java)
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val text = call.request.queryParameters["text"] ?: ""
        val size = call.request.queryParameters["size"]?.toIntOrNull()?.coerceIn(1, 250) ?: 20
        // Overfetch from DB since text filtering and permission checks reduce the result set.
        // This avoids returning 0 results when matching repos exist beyond the initial limit.
        val fetchLimit = if (text.isNotBlank()) (size * 5).coerceAtMost(1000) else size
        val repos = repoService.listRepositoriesByType(ArtifactType.NPM, limit = fetchLimit)

        val filteredRepos = if (text.isNotBlank()) {
            repos.filter { repo ->
                repo.name.contains(text, ignoreCase = true)
            }
        } else {
            repos
        }

        val repoData = filteredRepos.mapNotNull { repo ->
            val ns = repoService.getNamespace(repo.namespaceId)
            if (ns == null) {
                log.warn("Repository {} references non-existent namespace {}", repo.id, repo.namespaceId)
                return@mapNotNull null
            }
            val hasAccess = permissionEvaluator.evaluate(
                authenticationContext, "npm", ns.name, repo.name, null, ArtifactAction.PULL, ns.public
            )
            if (!hasAccess) return@mapNotNull null
            val packageName = if (ns.name.startsWith("@")) "${ns.name}/${repo.name}" else repo.name
            // Check namespace name against search text as well
            if (text.isNotBlank() && !packageName.contains(text, ignoreCase = true)) return@mapNotNull null
            val latestVersion = repoService.listVersions(repo.id, limit = 1).firstOrNull()?.version ?: "0.0.0"
            packageName to latestVersion
        }.take(size)
        val objects = buildJsonArray {
            for ((packageName, latestVersion) in repoData) {
                addJsonObject {
                    putJsonObject("package") {
                        put("name", packageName)
                        put("version", latestVersion)
                    }
                }
            }
        }

        val body = buildJsonObject {
            putJsonArray("objects") { objects.forEach { add(it) } }
            put("total", repoData.size)
        }
        call.respond(HttpStatusCode.OK, body)
    }
}
