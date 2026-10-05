package bosca.artifacts.maven.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Maximum allowed size for a single Maven artifact upload (1 GB). */
private const val MAX_MAVEN_UPLOAD_BYTES = 1024L * 1024 * 1024

/**
 * Handles Maven artifact uploads within a named repository.
 *
 * The URL structure is `/maven/{repository}/{standard maven path}` where the
 * repository is an explicit namespace identifier and the remaining path follows
 * standard Maven repository layout conventions.
 *
 * Each file (POM, JAR, sources, .module, etc.) is uploaded individually via PUT.
 * Checksum sidecar files (.sha1, .md5, .sha256) are accepted but not stored since
 * checksums are computed from content at upload time.
 */
@RouteController("/maven/{repository}/{path...}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class MavenPut(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val repository = call.pathParameters["repository"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val requestPath = call.pathParameters["path"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val coords = parseMavenPath("/$requestPath")
        if (coords == null) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        if (!permissionEvaluator.evaluate(authenticationContext, "maven", repository, "${coords.groupId}.${coords.artifactId}", coords.version, ArtifactAction.PUSH)) {
            if (authenticationContext.principal() == null) {
                call.response.header(HttpHeaders.WWWAuthenticate, """Basic realm="Bosca Maven Registry"""")
                call.respond(HttpStatusCode.Unauthorized, "")
            } else {
                call.respond(HttpStatusCode.Forbidden, "")
            }
            return
        }

        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength == null) {
            call.respond(HttpStatusCode.LengthRequired)
            return
        }
        if (contentLength > MAX_MAVEN_UPLOAD_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }

        if (isChecksumFile(coords.filename)) {
            call.respond(HttpStatusCode.OK)
            return
        }

        if (coords.version == null) {
            call.respond(HttpStatusCode.OK)
            return
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5 = MessageDigest.getInstance("MD5")
        val tempFile = withContext(Dispatchers.IO) { File.createTempFile("maven-upload-", ".tmp") }

        try {
            val digestOutput = DigestOutputStream(
                DigestOutputStream(
                    DigestOutputStream(
                        BufferedOutputStream(withContext(Dispatchers.IO) { tempFile.outputStream() }),
                        sha256
                    ), sha1
                ), md5
            )
            val bytesWritten = digestOutput.use { output ->
                call.request.bodyStreamTo(output)
            }

            val sha256Hex = sha256.digest().joinToString("") { "%02x".format(it) }
            val digest = "sha256:$sha256Hex"

            withContext(Dispatchers.IO) {
                tempFile.inputStream().use { input ->
                    blobService.store(digest, input, bytesWritten)
                }
            }

            val effectiveCoords = if (coords.filename?.endsWith(".pom") == true) {
                withContext(Dispatchers.IO) { parsePomFile(tempFile) } ?: coords
            } else {
                coords
            }

            val repoName = "${effectiveCoords.groupId}.${effectiveCoords.artifactId}"
            val versionString = effectiveCoords.version ?: coords.version

            val repo = repoService.findOrCreateRepository(repository, repoName, ArtifactType.MAVEN)
            val version = repoService.findVersion(repo.id, versionString)
                ?: repoService.createVersion(repo.id, versionString)

            val role = mavenRole(coords.filename ?: "artifact")
            val mediaType = guessMavenContentType(coords.filename ?: "")
            repoService.addVersionBlob(version.id, digest, role, coords.filename, mediaType)

            val sha1Hex = sha1.digest().joinToString("") { "%02x".format(it) }
            val md5Hex = md5.digest().joinToString("") { "%02x".format(it) }
            repoService.addVersionBlob(version.id, digest, "sha1-$role", sha1Hex, null)
            repoService.addVersionBlob(version.id, digest, "md5-$role", md5Hex, null)

            call.respond(HttpStatusCode.Created)
        } finally {
            withContext(Dispatchers.IO) {
                if (!tempFile.delete()) {
                    org.slf4j.LoggerFactory.getLogger("bosca.artifacts.maven.routes.MavenPut")
                        .warn("Failed to delete temp file: {}", tempFile.absolutePath)
                }
            }
        }
    }
}
