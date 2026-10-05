package bosca.artifacts.npm.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.security.service.AuthenticationContext
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("bosca.artifacts.npm.routes.NpmRouteSupport")

/** Maximum allowed Content-Length for an npm publish request (256 MB). */
private const val MAX_NPM_PUBLISH_BYTES = 256L * 1024 * 1024

/**
 * Retrieves the npm package metadata document for a given namespace and package name.
 * Builds the JSON response containing all versions, dist-tags, and tarball URLs
 * that npm/pnpm clients expect from a registry GET.
 */
internal suspend fun handleNpmGetPackage(
    call: ServerCall,
    namespace: String,
    name: String,
    repoService: ArtifactRepositoryService,
    blobService: BlobStorageService,
    permissionEvaluator: ArtifactPermissionEvaluator,
    auth: AuthenticationContext,
) {
    val ns = repoService.getNamespaceByName(namespace)
    permissionEvaluator.verify(auth, "npm", namespace, name, null, ArtifactAction.PULL, ns?.public ?: false)

    val repo = repoService.findRepository(namespace, name, ArtifactType.NPM)
    if (repo == null) {
        call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "not found") })
        return
    }

    val versions = repoService.listVersions(repo.id)
    val packageName = if (namespace.startsWith("@")) "$namespace/$name" else name
    val host = call.request.origin.let { "${it.scheme}://${it.host}:${it.port}" }

    val versionBlobsMap = versions.associateWith { version ->
        repoService.getVersionBlobs(version.id)
    }

    val versionsObj = buildJsonObject {
        for (version in versions) {
            putJsonObject(version.version) {
                put("name", packageName)
                put("version", version.version)
                version.metadata?.forEach { (k, v) -> put(k, v) }
                val blobs = versionBlobsMap[version] ?: emptyList()
                val tarball = blobs.find { it.role == "tarball" }
                if (tarball != null) {
                    putJsonObject("dist") {
                        val tarballFilename = tarball.filename ?: "$name-${version.version}.tgz"
                        put("tarball", "$host/npm/$packageName/-/$tarballFilename")
                        val sha1Blob = blobs.find { it.role == "tarball-sha1" }
                        if (sha1Blob != null) {
                            put("shasum", sha1Blob.filename ?: "")
                        }
                        put("integrity", "sha256-${java.util.Base64.getEncoder().encodeToString(hexToBytes(tarball.digest.removePrefix("sha256:")))}")
                    }
                }
            }
        }
    }

    val distTags = buildJsonObject {
        val latest = versions.firstOrNull()
        if (latest != null) {
            put("latest", latest.version)
        }
    }

    val body = buildJsonObject {
        put("_id", packageName)
        put("name", packageName)
        put("dist-tags", distTags)
        put("versions", versionsObj)
    }

    call.respond(HttpStatusCode.OK, body)
}

/**
 * Processes an npm publish request by parsing the package document, storing
 * tarballs as blobs, and creating version records for each new version.
 *
 * The npm publish protocol embeds base64-encoded tarballs inline in the JSON document.
 * Content-Length is validated upfront to reject oversized payloads before streaming starts.
 * The framework's [bosca.server.RequestBody] enforces `maxBodySize` with backpressure
 * during streaming to bound memory usage even if Content-Length is missing or dishonest.
 */
internal suspend fun handleNpmPublish(
    call: ServerCall,
    namespace: String,
    name: String,
    repoService: ArtifactRepositoryService,
    blobService: BlobStorageService,
    permissionEvaluator: ArtifactPermissionEvaluator,
    auth: AuthenticationContext,
) {
    permissionEvaluator.verify(auth, "npm", namespace, name, null, ArtifactAction.PUSH)

    val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (contentLength == null) {
        call.respond(HttpStatusCode.LengthRequired, buildJsonObject {
            put("error", "Content-Length header is required")
        })
        return
    }
    if (contentLength > MAX_NPM_PUBLISH_BYTES) {
        call.respond(HttpStatusCode.PayloadTooLarge, buildJsonObject {
            put("error", "package too large (max ${MAX_NPM_PUBLISH_BYTES / (1024 * 1024)}MB)")
        })
        return
    }

    val bodyBytes = call.request.bodyBytes()
    val doc = try {
        Json.parseToJsonElement(String(bodyBytes, Charsets.UTF_8)).jsonObject
    } catch (e: SerializationException) {
        call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "invalid JSON in request body") })
        return
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "request body is not a JSON object") })
        return
    }

    val attachments = doc["_attachments"]?.jsonObject ?: run {
        call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "missing _attachments") })
        return
    }

    val versionsDoc = doc["versions"]?.jsonObject ?: run {
        call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "missing versions") })
        return
    }

    val repo = repoService.findOrCreateRepository(namespace, name, ArtifactType.NPM)

    // Validate all versions upfront before creating any state, so that
    // validation errors never leave partially-published versions behind.
    data class PreparedVersion(
        val versionStr: String,
        val meta: JsonObject,
        val tarballBytes: ByteArray,
        val tarballFilename: String,
    )

    val prepared = mutableListOf<PreparedVersion>()
    for ((versionStr, versionData) in versionsDoc) {
        val versionMeta = versionData.jsonObject

        val existing = repoService.findVersion(repo.id, versionStr)
        if (existing != null) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject {
                put("error", "version $versionStr already exists")
            })
            return
        }

        val distObj = versionMeta["dist"]?.jsonObject
        val tarballUrl = distObj?.get("tarball")?.jsonPrimitive?.contentOrNull
        val tarballFilename = tarballUrl?.substringAfterLast("/") ?: "$name-$versionStr.tgz"

        // npm may key _attachments by the unscoped name ("pkg-1.0.0.tgz") or the
        // full scoped name ("@scope/pkg-1.0.0.tgz"). Try the derived filename first,
        // then fall back to the alternate form so both conventions are supported.
        val attachment = (attachments[tarballFilename]
            ?: attachments["$namespace/$tarballFilename"]
            ?: attachments["$name-$versionStr.tgz"])?.jsonObject
        val data = attachment?.get("data")?.jsonPrimitive?.contentOrNull
        if (data == null) {
            log.warn(
                "npm publish: no tarball data found for version {}; looked for keys [{}, {}/{}] in _attachments keys {}",
                versionStr, tarballFilename, namespace, tarballFilename, attachments.keys
            )
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("error", "missing tarball attachment for version $versionStr")
            })
            return
        }

        val tarballBytes = try {
            java.util.Base64.getDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("error", "invalid base64 in tarball attachment for version $versionStr")
            })
            return
        }

        prepared += PreparedVersion(versionStr, versionMeta, tarballBytes, tarballFilename)
    }

    // All validation passed — now create versions with rollback on failure.
    // Track created version IDs so we can clean up if a later version fails.
    val createdVersionIds = mutableListOf<bosca.serialization.UUID>()
    try {
        for (pv in prepared) {
            val digest = "sha256:" + sha256Hex(pv.tarballBytes)
            blobService.store(digest, pv.tarballBytes.inputStream(), pv.tarballBytes.size.toLong())

            val version = repoService.createVersion(repo.id, pv.versionStr, pv.meta)
            createdVersionIds += version.id
            repoService.addVersionBlob(version.id, digest, "tarball", pv.tarballFilename, "application/gzip")

            val sha1Hex = sha1Hex(pv.tarballBytes)
            repoService.addVersionBlob(version.id, digest, "tarball-sha1", sha1Hex, null)
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Roll back any versions that were successfully created
        for (versionId in createdVersionIds) {
            try {
                repoService.deleteVersion(versionId)
            } catch (rollbackEx: kotlin.coroutines.cancellation.CancellationException) {
                throw rollbackEx
            } catch (rollbackEx: Exception) {
                log.error("Failed to roll back version {} during publish cleanup", versionId, rollbackEx)
            }
        }
        log.error("npm publish failed, rolled back {} version(s)", createdVersionIds.size, e)
        call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
            put("error", "publish failed, all changes have been rolled back")
        })
        return
    }

    val body = buildJsonObject {
        put("ok", "package published")
        put("success", true)
    }
    call.respond(HttpStatusCode.Created, body)
}

/**
 * Locates and streams a package tarball by filename across all versions of
 * the given package.
 */
internal suspend fun handleNpmDownloadTarball(
    call: ServerCall,
    namespace: String,
    name: String,
    filename: String,
    repoService: ArtifactRepositoryService,
    blobService: BlobStorageService,
    permissionEvaluator: ArtifactPermissionEvaluator,
    auth: AuthenticationContext,
) {
    // Reject filenames with path traversal sequences or null bytes
    if (filename.contains("..") || filename.contains('/') || filename.contains('\\') || filename.contains('\u0000')) {
        call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "invalid filename") })
        return
    }

    val ns = repoService.getNamespaceByName(namespace)
    permissionEvaluator.verify(auth, "npm", namespace, name, null, ArtifactAction.PULL, ns?.public ?: false)

    val repo = repoService.findRepository(namespace, name, ArtifactType.NPM)
    if (repo == null) {
        call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "not found") })
        return
    }

    // Direct query by filename and role avoids iterating all versions
    val tarball = repoService.findVersionBlobByRepositoryFilenameAndRole(repo.id, filename, "tarball")
    if (tarball != null) {
        val blob = blobService.get(tarball.digest)
        if (blob != null) {
            val stream = blobService.getInputStream(tarball.digest)
            call.response.header(HttpHeaders.ContentLength, blob.size.toString())
            // Closed whatever happens: a HEAD request is answered without running the streaming block.
            stream.use { input ->
                // No time limit: a large blob on a slow link may take long; a client that stops reading
                // altogether is ended by the streaming response's stall timeout.
                call.respondStreaming(ContentType("application", "gzip"), HttpStatusCode.OK, timeLimit = null) { output -> output.copyFrom(input) }
            }
            return
        }
    }

    call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "tarball not found") })
}

/** Pattern for valid npm scope names (without the @ prefix). */
private val VALID_NPM_SCOPE = Regex("[a-zA-Z0-9_.-]+")

/** Pattern for valid npm package names. */
private val VALID_NPM_NAME = Regex("[a-zA-Z0-9_.-]+")

/**
 * Validates an npm scope name (without the @ prefix) to prevent injection
 * of traversal sequences or special characters into namespace lookups.
 */
internal fun isValidNpmScope(scope: String): Boolean = VALID_NPM_SCOPE.matches(scope)

/**
 * Validates an npm package name to prevent injection of traversal sequences
 * or special characters.
 */
internal fun isValidNpmName(name: String): Boolean = VALID_NPM_NAME.matches(name)

internal fun hexToBytes(hex: String): ByteArray {
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

internal fun sha256Hex(bytes: ByteArray): String = bosca.artifacts.sha256Hex(bytes)

internal fun sha1Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
