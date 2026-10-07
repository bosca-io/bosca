package bosca.cli.images

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** Reads published tags through the Docker Registry HTTP API, including GHCR token challenges. */
internal class ImageRegistry(private val username: String = "", private val password: String = "") {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Tags(val tags: List<String>? = null)
    @Serializable private data class Token(val token: String = "", @SerialName("access_token") val accessToken: String = "")

    /** Returns the highest stable numeric release tag actually published for [repository]. */
    suspend fun latest(repository: String): String = withContext(Dispatchers.IO) {
        val root = URI(if (repository.contains("://")) repository else "https://$repository")
        require(root.scheme in setOf("https", "http") && root.host != null && root.userInfo == null &&
            root.query == null && root.fragment == null) { "Invalid image repository" }
        val name = root.path.trim('/')
        require(name.matches(Regex("[a-z0-9]+(?:[._/-][a-z0-9]+)*"))) { "Invalid image repository path" }
        val endpoint = URI("${root.scheme}://${root.rawAuthority}/v2/$name/tags/list")
        var next: URI? = URI("$endpoint?n=1000")
        var authorization: String? = null
        val visited = mutableSetOf<URI>()
        val tags = mutableListOf<String>()
        while (next != null) {
            val page = next
            require(page.scheme == endpoint.scheme && page.rawAuthority == endpoint.rawAuthority && page.path == endpoint.path) {
                "Registry returned an invalid tag pagination URL"
            }
            require(visited.add(page)) { "Registry repeated a tag page" }
            var response = get(page, authorization)
            if (response.statusCode() == 401) {
                val challenge = response.headers().firstValue("WWW-Authenticate").orElse("")
                authorization = when {
                    challenge.startsWith("Basic ", ignoreCase = true) -> {
                        require(username.isNotBlank() && password.isNotBlank()) { "Registry requires a username and password" }
                        basic()
                    }
                    challenge.startsWith("Bearer ", ignoreCase = true) -> bearer(challenge, endpoint, name)
                    else -> error("Registry returned an unsupported authentication challenge")
                }
                response = get(page, authorization)
            }
            require(response.statusCode() == 200) { "Could not list image tags for $name (HTTP ${response.statusCode()})" }
            val batch = json.decodeFromString(Tags.serializer(), response.body()).tags.orEmpty()
            tags.addAll(batch)
            val link = response.headers().allValues("Link").asSequence().flatMap { it.split(',').asSequence() }
                .firstOrNull { Regex("rel=\"?next\"?", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            next = if (link != null) {
                val target = Regex("<([^>]+)>").find(link)?.groupValues?.get(1)
                    ?: error("Registry returned a malformed tag pagination link")
                page.resolve(target)
            } else if (batch.size == 1000) {
                // Bosca Artifacts uses `last` pagination without a Link response header.
                URI("$endpoint?n=1000&last=${encode(batch.last())}")
            } else null
        }
        latestStableTag(tags) ?: error("No stable release tags published for $name")
    }

    private fun get(uri: URI, authorization: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
        if (authorization != null) request.header("Authorization", authorization)
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun basic() = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    private fun bearer(challenge: String, endpoint: URI, name: String): String {
        val attributes = Regex("([a-z_]+)=\"([^\"]*)\"", RegexOption.IGNORE_CASE).findAll(challenge)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val realm = URI(attributes["realm"] ?: error("Registry token challenge has no realm"))
        require(realm.scheme == "https" || (endpoint.scheme == "http" && realm.scheme == "http" && realm.rawAuthority == endpoint.rawAuthority)) {
            "Registry token endpoint must use HTTPS"
        }
        require(realm.host != null && realm.userInfo == null && realm.fragment == null) { "Invalid registry token endpoint" }
        val query = listOf("service" to attributes["service"].orEmpty(), "scope" to "repository:$name:pull")
            .joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val uri = URI("$realm${if (realm.query == null) "?" else "&"}$query")
        // Credentials belong to the configured registry; external token services get anonymous requests.
        val auth = if (username.isNotBlank() && realm.rawAuthority == endpoint.rawAuthority) basic() else null
        val response = get(uri, auth)
        require(response.statusCode() == 200) { "Could not authenticate to image registry (HTTP ${response.statusCode()})" }
        val token = json.decodeFromString(Token.serializer(), response.body()).let { it.token.ifBlank { it.accessToken } }
        require(token.isNotBlank()) { "Registry returned an empty access token" }
        return "Bearer $token"
    }
}

private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

/** Ignores moving aliases and prereleases and compares release components numerically. */
internal fun latestStableTag(tags: List<String>): String? {
    val stable = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:\\+[A-Za-z0-9.-]+)?")
    return tags.mapNotNull { tag -> stable.matchEntire(tag)?.let { tag to it.groupValues.drop(1).map(::BigInteger) } }
        .maxWithOrNull { left, right ->
            left.second.zip(right.second).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) }
                ?: left.first.compareTo(right.first)
        }?.first
}
