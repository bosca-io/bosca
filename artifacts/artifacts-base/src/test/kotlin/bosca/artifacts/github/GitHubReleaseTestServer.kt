package bosca.artifacts.github

import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.security.MessageDigest

/** Stateful provider fixture for response-loss retries and byte-for-byte upload assertions. */
internal class GitHubReleaseTestServer : AutoCloseable {
    val server = MockWebServer()
    val requests = mutableListOf<RecordedRequest>()
    val files = linkedMapOf<String, ByteArray>()
    val assetDigests = mutableMapOf<String, String>()
    var repositoryId = 123L
    var commit = "1".repeat(40)
    var annotated = false
    var hasRelease = false
    var draft = true
    var prerelease = false
    var immutable = false
    var marker: String? = null
    var tag = "v1.0.0"
    var starter: String? = null
    var uploadUrl: String? = null
    var uploadFailures = 0
    var loseUploadResponse = false
    var verificationFailures = 0
    var creates = 0
    var uploads = 0
    var deletes = 0
    var publications = 0
    var assetNameOverride: String? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.url
                val path = url.encodedPath
                val method = request.method
                if (path == "/repos/acme/tool") return response("""{"id":$repositoryId}""")
                if (path.startsWith("/repos/acme/tool/git/ref/tags/")) {
                    return response("""{"object":{"type":"${if (annotated) "tag" else "commit"}","sha":"${if (annotated) "2".repeat(40) else commit}"}}""")
                }
                if (path.startsWith("/repos/acme/tool/git/tags/")) return response("""{"object":{"type":"commit","sha":"$commit"}}""")
                if (path == "/repos/acme/tool/releases") {
                    if (method == "GET") return response(if (hasRelease && url.queryParameter("page") == "1") "[${release()}]" else "[]")
                    if (method == "POST") {
                        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
                        check(body.getValue("draft").jsonPrimitive.boolean)
                        check(body.getValue("make_latest").jsonPrimitive.content == "false")
                        hasRelease = true
                        draft = true
                        marker = body.getValue("body").jsonPrimitive.content
                        prerelease = body.getValue("prerelease").jsonPrimitive.boolean
                        creates++
                        return response(release(), 201)
                    }
                }
                if (path == "/repos/acme/tool/releases/42" && method == "PATCH") {
                    draft = false
                    publications++
                    return response(release())
                }
                if (path.equals("/repos/acme/tool/releases/42/assets", ignoreCase = true)) {
                    if (method == "GET") {
                        if (!draft && verificationFailures > 0) {
                            verificationFailures--
                            return response("""{"message":"credential-must-not-appear"}""", 503)
                        }
                        if (url.queryParameter("page") != "1") return response("[]")
                        val entries = files.entries.map { (name, bytes) -> asset(name, bytes.size.toLong()) }.toMutableList()
                        starter?.let { entries += asset(it, 0, "starter") }
                        return response(entries.joinToString(",", "[", "]"))
                    }
                    if (method == "POST") {
                        uploads++
                        val name = requireNotNull(url.queryParameter("name"))
                        val bytes = requireNotNull(request.body).toByteArray()
                        if (uploadFailures > 0) {
                            uploadFailures--
                            if (loseUploadResponse) files[name] = bytes
                            else starter = name
                            return response("""{"message":"credential-must-not-appear"}""", 502)
                        }
                        files[name] = bytes
                        return response(asset(assetNameOverride ?: name, bytes.size.toLong(), digest = digest(bytes)), 201)
                    }
                }
                if (path == "/repos/acme/tool/releases/assets/9" && method == "DELETE") {
                    deletes++
                    starter = null
                    return response("", 204)
                }
                return response("""{"message":"unexpected route $method $path"}""", 500)
            }
        }
        server.start()
    }

    val base: String get() = server.url("/").toString().removeSuffix("/")
    private fun quote(value: String) = JsonPrimitive(value).toString()
    private fun release() = """{"id":42,"tag_name":${quote(tag)},"upload_url":${quote(uploadUrl ?: "$base/repos/acme/tool/releases/42/assets{?name,label}")},"draft":$draft,"prerelease":$prerelease,"immutable":$immutable,"body":${marker?.let(::quote) ?: "null"}}"""
    private fun asset(name: String, size: Long, state: String = "uploaded", digest: String? = assetDigests[name] ?: files[name]?.let(::digest)) =
        """{"id":9,"name":${quote(name)},"size":$size,"state":${quote(state)},"digest":${digest?.let(::quote) ?: "null"}}"""
    private fun response(body: String, status: Int = 200) = MockResponse.Builder().code(status).body(body).build()
    override fun close() = server.close()

    companion object {
        fun digest(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
