package bosca.artifacts.sync

import bosca.artifacts.model.ArtifactSyncDestination
import bosca.artifacts.service.BlobStorageService
import bosca.server.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.security.MessageDigest
import java.time.Duration

/** Copies stored OCI/Docker schema-2 image graphs without a local Docker daemon or rebuilding images. */
class GhcrClient(
    private val blobs: BlobStorageService,
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofMinutes(30)).build(),
    registryUrl: String = "https://ghcr.io",
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val registry = registryUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Copies the stored graph using its root manifest's publication media type when the JSON omits it.
     * Uploads dependencies before assigning the remote tag. A superseded request leaves that tag untouched.
     */
    suspend fun push(destination: ArtifactSyncDestination, credential: String, tag: String, digest: String, mediaType: String?, beforeTag: suspend () -> Boolean): Boolean {
        val manifests = linkedMapOf<String, StoredManifest>()
        val content = linkedMapOf<String, Long>()
        collect(digest, null, mediaType?.substringBefore(';')?.trim(), manifests, content, mutableSetOf())
        // Validate the complete stored graph before making remote changes.
        for ((blobDigest, size) in content) {
            remoteRequire(blobs.get(blobDigest)?.size == size, "A stored image blob is missing or has a different size")
        }
        val bearer = token(destination, credential)
        for ((blobDigest, size) in content) uploadBlob(destination, bearer, blobDigest, size)
        for ((manifestDigest, manifest) in manifests) {
            putManifest(destination, bearer, manifestDigest, manifest)
        }
        if (!beforeTag()) return false
        val manifest = manifests[digest] ?: throw GhcrException("Stored root manifest is missing")
        putManifest(destination, bearer, tag, manifest)
        request(destination, bearer, "manifests", tag).head().build().let { request ->
            client.newCall(request).await().use { response ->
                requireStatus(response, 200)
                remoteRequire(response.header("Docker-Content-Digest") == digest, "GHCR tag digest differs from the stored image")
            }
        }
        return true
    }

    private suspend fun collect(
        digest: String, expectedSize: Long?, expectedType: String?,
        manifests: MutableMap<String, StoredManifest>, content: MutableMap<String, Long>, visiting: MutableSet<String>,
    ) {
        validateDigest(digest)
        if (digest in manifests) {
            val stored = manifests.getValue(digest)
            remoteRequire(expectedSize == null || stored.bytes.size.toLong() == expectedSize, "Image manifest descriptor size differs")
            return
        }
        remoteRequire(visiting.size < 64 && visiting.add(digest), "Image manifest graph is cyclic or too deep")
        val blob = blobs.get(digest) ?: throw GhcrException("A stored image manifest is missing")
        remoteRequire(expectedSize == null || blob.size == expectedSize, "Image manifest descriptor size differs")
        val bytes = withContext(Dispatchers.IO) { blobs.getInputStream(digest).use { it.readBytes() } }
        remoteRequire(sha256(bytes) == digest, "Stored image manifest digest differs")
        val decoded = json.decodeFromString(ImageManifest.serializer(), bytes.decodeToString())
        remoteRequire(decoded.schemaVersion == 2, "GHCR syncing requires OCI or Docker schema-2 manifests")
        val mediaType = decoded.mediaType ?: expectedType ?: if (decoded.manifests != null) DOCKER_INDEX else DOCKER_MANIFEST
        remoteRequire(mediaType in MANIFEST_TYPES, "Unsupported image manifest media type")
        decoded.subject?.let { collect(it.digest, it.size, it.mediaType, manifests, content, visiting) }
        val children = decoded.manifests
        if (children != null) {
            remoteRequire(mediaType == OCI_INDEX || mediaType == DOCKER_INDEX, "Image index has an invalid media type")
            for (child in children) collect(child.digest, child.size, child.mediaType, manifests, content, visiting)
        } else {
            val config = decoded.config ?: throw GhcrException("Image manifest config is missing")
            collectBlob(config, content, allowExternal = false)
            for (layer in decoded.layers) collectBlob(layer, content, allowExternal = true)
        }
        visiting.remove(digest)
        manifests[digest] = StoredManifest(bytes, mediaType)
    }

    private fun collectBlob(descriptor: ImageDescriptor, content: MutableMap<String, Long>, allowExternal: Boolean) {
        validateDigest(descriptor.digest)
        remoteRequire(descriptor.size >= 0, "Invalid image blob size")
        // Foreign layers remain external references in the original manifest, including when cached locally.
        if (allowExternal && descriptor.mediaType in EXTERNAL_LAYER_TYPES && descriptor.urls.isNotEmpty()) return
        val previous = content.put(descriptor.digest, descriptor.size)
        remoteRequire(previous == null || previous == descriptor.size, "Image blob descriptors disagree")
    }

    private suspend fun token(destination: ArtifactSyncDestination, credential: String): String {
        val url = registry.newBuilder().addPathSegment("token")
            .addQueryParameter("service", registry.host)
            .addQueryParameter("scope", "repository:${destination.remoteRepository}:pull,push").build()
        val request = Request.Builder().url(url).header("Authorization", Credentials.basic(destination.username, credential)).build()
        val response = client.newCall(request).await().use {
            requireStatus(it, 200)
            json.decodeFromString(RegistryToken.serializer(), it.body.string())
        }
        return (response.token ?: response.accessToken)?.takeIf { it.isNotBlank() }
            ?: throw GhcrException("GHCR did not return a registry token")
    }

    private suspend fun uploadBlob(destination: ArtifactSyncDestination, bearer: String, digest: String, size: Long) {
        client.newCall(request(destination, bearer, "blobs", digest).head().build()).await().use {
            if (it.code == 200) return
            requireStatus(it, 404)
        }
        val location = client.newCall(
            request(destination, bearer, "blobs", "uploads", "")
                .post(ByteArray(0).toRequestBody(null)).build()
        ).await().use {
            requireStatus(it, 202)
            uploadLocation(it)
        }
        blobs.getInputStream(digest).use { input ->
            val body = object : RequestBody() {
                override fun contentType() = OCTET_STREAM
                override fun contentLength() = size
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    sink.writeAll(input.source())
                }
            }
            val url = location.newBuilder().addQueryParameter("digest", digest).build()
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $bearer").put(body).build()).await().use {
                requireStatus(it, 201)
                remoteRequire(it.header("Docker-Content-Digest") == digest, "GHCR uploaded blob digest differs")
            }
        }
    }

    private fun uploadLocation(response: Response): HttpUrl {
        val value = response.header("Location") ?: throw GhcrException("GHCR upload location is missing")
        val url = response.request.url.resolve(value) ?: throw GhcrException("GHCR upload location is invalid")
        remoteRequire(
            url.scheme == registry.scheme && url.host == registry.host && url.port == registry.port &&
                    url.username.isEmpty() && url.password.isEmpty() && url.fragment == null, "GHCR returned an unexpected upload location"
        )
        return url
    }

    private suspend fun putManifest(destination: ArtifactSyncDestination, bearer: String, reference: String, manifest: StoredManifest) {
        client.newCall(
            request(destination, bearer, "manifests", reference)
                .put(manifest.bytes.toRequestBody(manifest.mediaType.toMediaType())).build()
        ).await().use {
            requireStatus(it, 201)
            remoteRequire(it.header("Docker-Content-Digest") == sha256(manifest.bytes), "GHCR uploaded manifest digest differs")
        }
    }

    private fun request(destination: ArtifactSyncDestination, bearer: String, vararg path: String): Request.Builder {
        val url = registry.newBuilder().addPathSegment("v2").apply {
            destination.remoteRepository.split('/').forEach(::addPathSegment)
            path.forEach(::addPathSegment)
        }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer $bearer").header("Accept", MANIFEST_TYPES.joinToString(", "))
    }

    private fun validateDigest(digest: String) {
        remoteRequire(digest.matches(Regex("sha256:[0-9a-f]{64}")), "GHCR syncing requires SHA-256 image digests")
    }

    private fun requireStatus(response: Response, expected: Int) {
        remoteRequire(response.code == expected, "GHCR artifact sync failed: HTTP ${response.code}")
    }

    private fun remoteRequire(condition: Boolean, message: String) {
        if (!condition) throw GhcrException(message)
    }

    private data class StoredManifest(val bytes: ByteArray, val mediaType: String)

    companion object {
        private const val OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
        private const val OCI_INDEX = "application/vnd.oci.image.index.v1+json"
        private const val DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json"
        private const val DOCKER_INDEX = "application/vnd.docker.distribution.manifest.list.v2+json"
        private val MANIFEST_TYPES = setOf(OCI_MANIFEST, OCI_INDEX, DOCKER_MANIFEST, DOCKER_INDEX)
        private val EXTERNAL_LAYER_TYPES = setOf(
            "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip",
            "application/vnd.oci.image.layer.nondistributable.v1.tar",
            "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip",
            "application/vnd.oci.image.layer.nondistributable.v1.tar+zstd",
        )
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        internal fun sha256(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Messages contain status and contract errors, never credentials or provider response bodies. */
class GhcrException(message: String) : IllegalStateException(message)

@Serializable
private data class RegistryToken(val token: String? = null, @SerialName("access_token") val accessToken: String? = null)
@Serializable
private data class ImageDescriptor(val digest: String, val size: Long, val mediaType: String, val urls: List<String> = emptyList())
@Serializable
private data class ImageManifest(
    val schemaVersion: Int,
    val mediaType: String? = null,
    val manifests: List<ImageDescriptor>? = null,
    val config: ImageDescriptor? = null,
    val layers: List<ImageDescriptor> = emptyList(),
    val subject: ImageDescriptor? = null,
)
