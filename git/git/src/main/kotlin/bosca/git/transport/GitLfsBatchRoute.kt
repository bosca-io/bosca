package bosca.git.transport

import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.LfsObjectService
import bosca.git.service.RepositoryService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Negotiates authenticated Basic Transfer URLs for Git LFS uploads and downloads. */
@RouteController(
    path = "/{owner}/{repo}.git/info/lfs/objects/batch",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.OPTIONAL
)
class GitLfsBatchRoute(
    private val repositoryService: RepositoryService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
    private val lfs: LfsObjectService,
    private val application: BoscaApplication,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val body = call.request.bodyText()
        val request = try {
            lfsJson.decodeFromString(LfsBatchRequest.serializer(), body)
        } catch (_: SerializationException) {
            return call.respondLfsError(HttpStatusCode.BadRequest, "Invalid LFS batch request")
        }
        val action = when (request.operation) {
            "download" -> PermissionAction.VIEW
            "upload" -> PermissionAction.EDIT
            else -> return call.respondLfsError(HttpStatusCode.BadRequest, "Unsupported LFS operation")
        }
        val repository = resolveLfsRepository(call, authenticationContext, repositoryService, permissionEvaluator, action) ?: return
        if (request.hashAlgorithm != "sha256") {
            return call.respondLfsError(HttpStatusCode.UnprocessableEntity, "Only sha256 LFS objects are supported")
        }
        if (request.transfers != null && "basic" !in request.transfers) {
            return call.respondLfsError(HttpStatusCode.UnprocessableEntity, "The basic LFS transfer adapter is required")
        }
        val baseUrl = application.environment.config.propertyOrNull("git.url")?.getString()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return call.respondLfsError(HttpStatusCode.ServiceUnavailable, "Git URL is not configured")
        val owner = encodePathSegment(call.pathParameters["owner"].orEmpty())
        val repo = encodePathSegment(call.pathParameters["repo"].orEmpty())
        val transferUrl = "$baseUrl/$owner/$repo.git/info/lfs/objects"
        val headers = call.request.headers["Authorization"]?.let { mapOf("Authorization" to it) }
        val objects = request.objects.map { obj ->
            if (!validLfsOid(obj.oid) || obj.size < 0) {
                LfsBatchResponseObject(obj.oid, obj.size, error = LfsObjectError(422, "Invalid LFS object oid or size"))
            } else {
                val existing = lfs.findByOid(repository.id, obj.oid)
                when {
                    existing != null && existing.size != obj.size ->
                        LfsBatchResponseObject(obj.oid, obj.size, error = LfsObjectError(422, "LFS object size does not match"))
                    request.operation == "download" && existing == null ->
                        LfsBatchResponseObject(obj.oid, obj.size, error = LfsObjectError(404, "LFS object not found"))
                    request.operation == "upload" && existing != null ->
                        LfsBatchResponseObject(obj.oid, obj.size)
                    else -> {
                        val actions = mutableMapOf(request.operation to LfsAction("$transferUrl/${obj.oid}", headers))
                        if (request.operation == "upload") {
                            actions["verify"] = LfsAction("$transferUrl/verify", headers)
                        }
                        LfsBatchResponseObject(obj.oid, obj.size, actions = actions)
                    }
                }
            }
        }
        call.respondBytes(
            lfsJson.encodeToString(LfsBatchResponse.serializer(), LfsBatchResponse(objects = objects)).toByteArray(),
            lfsContentType,
            HttpStatusCode.OK,
        )
    }

    private fun encodePathSegment(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

@Serializable
private data class LfsBatchRequest(
    val operation: String,
    val objects: List<LfsObjectRequest>,
    val transfers: List<String>? = null,
    val ref: LfsRef? = null,
    @SerialName("hash_algo") val hashAlgorithm: String = "sha256",
)

@Serializable
private data class LfsRef(val name: String)

@Serializable
private data class LfsBatchResponse(
    val transfer: String = "basic",
    val objects: List<LfsBatchResponseObject>,
    @SerialName("hash_algo") val hashAlgorithm: String = "sha256",
)

@Serializable
private data class LfsBatchResponseObject(
    val oid: String,
    val size: Long,
    val actions: Map<String, LfsAction>? = null,
    val error: LfsObjectError? = null,
)

@Serializable
private data class LfsAction(val href: String, val header: Map<String, String>? = null)

@Serializable
private data class LfsObjectError(val code: Int, val message: String)
