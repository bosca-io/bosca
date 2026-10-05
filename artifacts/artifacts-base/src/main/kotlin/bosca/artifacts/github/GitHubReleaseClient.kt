package bosca.artifacts.github

import bosca.artifacts.model.ArtifactPublication
import bosca.artifacts.model.ArtifactPublicationDestination
import bosca.artifacts.model.ArtifactPublicationFile
import bosca.artifacts.service.BlobStorageService
import bosca.server.http.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.time.Duration

/** GitHub release API adapter. Existing uploaded assets are immutable inputs, never replacement candidates. */
class GitHubReleaseClient(
    private val blobs: BlobStorageService,
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofMinutes(15)).build(),
    private val apiUrl: String = "https://api.github.com",
    private val uploadsUrl: String = "https://uploads.github.com",
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun verifyRepository(destination: ArtifactPublicationDestination, token: String) {
        val remote = json.decodeFromString(GitHubRepositoryIdentity.serializer(),
            execute(request(destination, token).build()))
        requireRemote(remote.id == destination.githubRepositoryId, "GitHub repository no longer matches the destination")
    }

    suspend fun publish(destination: ArtifactPublicationDestination, token: String, publication: ArtifactPublication): Long {
        verifyRepository(destination, token)
        verifyTag(destination, token, publication)
        val marker = "<!-- bosca-artifact-publication:${publication.id} -->"
        val existing = findRelease(destination, token, publication.tagName)
        val release = existing ?: json.decodeFromString(GitHubRelease.serializer(), execute(
            request(destination, token, "releases").post(json.encodeToString(GitHubCreateRelease.serializer(),
                GitHubCreateRelease(publication.tagName, publication.tagName, marker, publication.prerelease)).toRequestBody(JSON_TYPE)).build(),
        ))
        requireRemote(release.tagName == publication.tagName && release.prerelease == publication.prerelease,
            "GitHub release does not match the requested version")
        requireRemote(publication.releaseId == null || release.id == publication.releaseId, "GitHub release identity changed")
        requireRemote(!release.draft || release.body == marker, "An unrelated GitHub draft release already uses this tag")
        val assets = assets(destination, token, release.id).groupBy { it.name }
        for (file in publication.files) {
            val matches = assets[file.filename].orEmpty()
            requireRemote(matches.size <= 1, "Multiple GitHub assets use the requested filename")
            val existingAsset = matches.singleOrNull()
            if (existingAsset != null && existingAsset.state == "starter" && existingAsset.size == 0L && release.body == marker && release.draft) {
                execute(request(destination, token, "releases", "assets", existingAsset.id.toString()).delete().build())
            } else if (existingAsset != null) {
                verifyAsset(file, existingAsset)
                continue
            }
            requireRemote(!release.immutable, "The GitHub release is immutable and is missing an asset")
            upload(destination, token, release, file)
        }
        if (release.draft) {
            val published = json.decodeFromString(GitHubRelease.serializer(), execute(request(destination, token, "releases", release.id.toString())
                .patch("{\"draft\":false}".toRequestBody(JSON_TYPE)).build()))
            requireRemote(published.id == release.id && !published.draft && published.tagName == publication.tagName,
                "GitHub release was not published")
        }
        return release.id
    }

    suspend fun verify(destination: ArtifactPublicationDestination, token: String, publication: ArtifactPublication) {
        verifyRepository(destination, token)
        verifyTag(destination, token, publication)
        val release = findRelease(destination, token, publication.tagName)
            ?: throw GitHubPublicationException("Published GitHub release is missing")
        requireRemote(release.id == publication.releaseId && !release.draft && release.prerelease == publication.prerelease,
            "Published GitHub release does not match its recorded identity")
        val assets = assets(destination, token, release.id).groupBy { it.name }
        for (file in publication.files) {
            val asset = assets[file.filename].orEmpty().singleOrNull()
                ?: throw GitHubPublicationException("Published GitHub asset is missing or ambiguous")
            verifyAsset(file, asset)
        }
    }

    private fun verifyAsset(file: ArtifactPublicationFile, asset: GitHubReleaseAsset) {
        requireRemote(asset.state == "uploaded" && asset.name == file.filename && asset.size == file.size && asset.digest == file.digest,
            "GitHub asset content differs from the stored artifact")
    }

    private suspend fun verifyTag(destination: ArtifactPublicationDestination, token: String, publication: ArtifactPublication) {
        val ref = json.decodeFromString(GitHubTagRef.serializer(), execute(request(destination, token,
            "git", "ref", "tags", publication.tagName).build()))
        var target = ref.target
        val seen = mutableSetOf<String>()
        while (target.type == "tag") {
            requireRemote(seen.size < 16 && seen.add(target.sha), "GitHub annotated tag cannot be resolved")
            target = json.decodeFromString(GitHubAnnotatedTag.serializer(), execute(request(destination, token,
                "git", "tags", target.sha).build())).target
        }
        requireRemote(target.type == "commit" && target.sha == publication.commitSha, "GitHub tag does not point to the requested commit")
    }

    private suspend fun findRelease(destination: ArtifactPublicationDestination, token: String, tag: String): GitHubRelease? {
        var page = 1
        val matches = mutableListOf<GitHubRelease>()
        while (true) {
            val url = url(destination, "releases").newBuilder().addQueryParameter("per_page", "100").addQueryParameter("page", (page++).toString()).build()
            val releases = json.decodeFromString(ListSerializer(GitHubRelease.serializer()), execute(request(token, url).build()))
            matches += releases.filter { it.tagName == tag }
            if (releases.isEmpty()) break
        }
        requireRemote(matches.size <= 1, "Multiple GitHub releases use the requested tag")
        return matches.singleOrNull()
    }

    private suspend fun assets(destination: ArtifactPublicationDestination, token: String, id: Long): List<GitHubReleaseAsset> {
        var page = 1
        val result = mutableListOf<GitHubReleaseAsset>()
        while (true) {
            val url = url(destination, "releases", id.toString(), "assets").newBuilder()
                .addQueryParameter("per_page", "100").addQueryParameter("page", (page++).toString()).build()
            val assets = json.decodeFromString(ListSerializer(GitHubReleaseAsset.serializer()), execute(request(token, url).build()))
            result += assets
            if (assets.isEmpty()) break
        }
        return result
    }

    private suspend fun upload(destination: ArtifactPublicationDestination, token: String, release: GitHubRelease, file: ArtifactPublicationFile) {
        val upload = release.uploadUrl.removeSuffix("{?name,label}").toHttpUrl()
        val allowed = uploadsUrl.toHttpUrl()
        val path = upload.pathSegments
        val expectedTail = listOf("releases", release.id.toString(), "assets")
        requireRemote(upload.scheme == allowed.scheme && upload.host == allowed.host && upload.port == allowed.port &&
            path.size == 6 && path[0] == "repos" && path[1].equals(destination.owner, ignoreCase = true) &&
            path[2].equals(destination.githubRepository, ignoreCase = true) && path.drop(3) == expectedTail &&
            upload.query == null && upload.username.isEmpty() && upload.password.isEmpty(),
            "GitHub returned an unexpected asset upload URL")
        blobs.getInputStream(file.digest).use { input ->
            val body = object : RequestBody() {
                override fun contentType() = file.mediaType.toMediaType()
                override fun contentLength() = file.size
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
            }
            val asset = json.decodeFromString(GitHubReleaseAsset.serializer(), execute(request(token,
                upload.newBuilder().addQueryParameter("name", file.filename).build()).post(body).build()))
            verifyAsset(file, asset)
        }
    }

    private fun url(destination: ArtifactPublicationDestination, vararg path: String) = apiUrl.toHttpUrl().newBuilder().apply {
        addPathSegment("repos"); addPathSegment(destination.owner); addPathSegment(destination.githubRepository)
        path.forEach(::addPathSegment)
    }.build()

    private fun request(destination: ArtifactPublicationDestination, token: String, vararg path: String) = request(token, url(destination, *path))
    private fun request(token: String, url: HttpUrl) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")

    private suspend fun execute(request: Request): String = client.newCall(request).await().use { response ->
        if (!response.isSuccessful) throw GitHubPublicationException("GitHub artifact publication failed: HTTP ${response.code}")
        response.body.string()
    }

    private fun requireRemote(condition: Boolean, message: String) { if (!condition) throw GitHubPublicationException(message) }

    companion object { private val JSON_TYPE = "application/json; charset=utf-8".toMediaType() }
}

/** Public error messages omit credentials and provider response bodies. */
class GitHubPublicationException(message: String) : IllegalStateException(message)

@Serializable private data class GitHubRepositoryIdentity(val id: Long)
@Serializable private data class GitHubGitObject(val type: String, val sha: String)
@Serializable private data class GitHubTagRef(@SerialName("object") val target: GitHubGitObject)
@Serializable private data class GitHubAnnotatedTag(@SerialName("object") val target: GitHubGitObject)
@Serializable private data class GitHubRelease(
    val id: Long, @SerialName("tag_name") val tagName: String, @SerialName("upload_url") val uploadUrl: String,
    val body: String? = null, val draft: Boolean, val prerelease: Boolean, val immutable: Boolean = false,
)
@Serializable private data class GitHubReleaseAsset(val id: Long, val name: String, val size: Long, val state: String, val digest: String? = null)
@Serializable private data class GitHubCreateRelease(
    @SerialName("tag_name") val tagName: String, val name: String, val body: String, val prerelease: Boolean,
    val draft: Boolean = true, @SerialName("make_latest") val makeLatest: String = "false",
)
