package bosca.bml.message.server

import bosca.bml.message.BmlMessageArtifacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * HTTP client for the raw artifacts registry (the `ArtifactsRegistryClient` precedent): lists an
 * message project's published versions and downloads its jar, verifying the registry's SHA-256
 * digest before the file is trusted. All calls run on [Dispatchers.IO] (OkHttp is blocking).
 */
class MessageArtifactClient(
    baseUrl: String,
    private val token: String?,
    private val http: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    /** One published file of a version, as the registry lists it (`digest` is `sha256:<hex>`). */
    @Serializable
    data class FileInfo(val filename: String, val digest: String, val mediaType: String? = null)

    @Serializable
    data class VersionInfo(val version: String, val created: String, val files: List<FileInfo> = emptyList()) {
        /** The version's message jar entry, or null when the version carries none. */
        val jar: FileInfo? get() = files.firstOrNull { it.filename.endsWith(".jar") }
    }

    @Serializable
    data class VersionsResponse(val name: String, val versions: List<VersionInfo> = emptyList())

    /** All published versions of [project] in the `bml-message` namespace (empty when unknown). */
    suspend fun versions(project: String): List<VersionInfo> = withContext(Dispatchers.IO) {
        val request = authorized(
            Request.Builder().url("$base/raw/${BmlMessageArtifacts.NAMESPACE}/api/$project").get(),
        ).build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> emptyList()
                !response.isSuccessful ->
                    error("artifacts registry returned ${response.code} listing ${BmlMessageArtifacts.NAMESPACE}/$project")
                else -> json.decodeFromString(VersionsResponse.serializer(), response.body.string()).versions
            }
        }
    }

    /**
     * Every raw repository under the bml-message namespace — the discovery surface, so a
     * restarted server finds all published projects without seeds or a live publish event.
     */
    suspend fun projects(): List<String> = withContext(Dispatchers.IO) {
        val request = authorized(
            Request.Builder().url("$base/raw/${BmlMessageArtifacts.NAMESPACE}/api").get(),
        ).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("artifacts registry returned ${response.code} listing namespace ${BmlMessageArtifacts.NAMESPACE}")
            }
            json.decodeFromString(RepositoriesResponse.serializer(), response.body.string()).repositories
        }
    }

    @kotlinx.serialization.Serializable
    data class RepositoriesResponse(val namespace: String, val repositories: List<String> = emptyList())

    /**
     * The latest published version of [project], or null. Versions are minted with a UTC-timestamp
     * prefix (lexicographically monotonic), with the registry's `created` stamp as the
     * tiebreaker for anything hand-published outside that scheme.
     */
    suspend fun latestVersion(project: String): VersionInfo? =
        versions(project).filter { it.jar != null }.maxWithOrNull(compareBy({ it.version }, { it.created }))

    /**
     * Download one file of a project version to [target], verifying [expectedDigest]
     * (`sha256:<hex>`, from the version listing) before the file lands — a failed verification
     * deletes the download and throws, so a corrupt artifact can never activate.
     */
    suspend fun download(
        project: String,
        version: String,
        filename: String,
        expectedDigest: String?,
        target: File,
    ): File = withContext(Dispatchers.IO) {
        val request = authorized(
            Request.Builder().url("$base/raw/${BmlMessageArtifacts.NAMESPACE}/$project/$version/$filename").get(),
        ).build()
        val temp = File(target.parentFile.also { it.mkdirs() }, "${target.name}.downloading")
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("artifacts registry returned ${response.code} for ${BmlMessageArtifacts.NAMESPACE}/$project/$version/$filename")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            val actual = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedDigest != null && !actual.equals(expectedDigest, ignoreCase = true)) {
                temp.delete()
                error("digest mismatch for $project/$version/$filename: expected $expectedDigest, downloaded $actual")
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target
    }

    private fun authorized(builder: Request.Builder): Request.Builder = builder.apply {
        token?.let {
            val credentials = Base64.getEncoder().encodeToString("api_token:$it".toByteArray(Charsets.UTF_8))
            header("Authorization", "Basic $credentials")
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(60))
            .build()
    }
}
