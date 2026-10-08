package bosca.artifacts.sync

import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A registry that requires referenced content, except layers retained as external references. */
internal class GhcrTestServer : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val blobs = ConcurrentHashMap<String, ByteArray>()
    val manifests = ConcurrentHashMap<String, ByteArray>()
    val tags = ConcurrentHashMap<String, String>()
    var uploadFailures = 0
    var loseUploadResponse = false
    var tagFailures = 0
    var uploadLocation: String? = null
    var verificationDigest: String? = null
    val base get() = server.url("/").toString().removeSuffix("/")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.url
                val path = url.encodedPath
                val method = request.method
                if (path == "/token") return response(200, """{"token":"registry-token"}""")
                if (request.headers["Authorization"] != "Bearer registry-token") return response(401)
                if ("/blobs/uploads" in path) {
                    if (method == "POST") return response(202).newBuilder()
                        .addHeader("Location", uploadLocation ?: "${path.trimEnd('/')}/1?state=keep-me").build()
                    if (method == "PUT") {
                        if (url.queryParameter("state") != "keep-me") return response(400)
                        val bytes = requireNotNull(request.body).toByteArray()
                        val digest = GhcrClient.sha256(bytes)
                        if (url.queryParameter("digest") != digest) return response(400)
                        if (uploadFailures > 0) {
                            uploadFailures--
                            if (loseUploadResponse) blobs[digest] = bytes
                            return response(503, "provider-secret-must-not-appear")
                        }
                        blobs[digest] = bytes
                        return response(201).newBuilder().addHeader("Docker-Content-Digest", digest).build()
                    }
                }
                if ("/blobs/" in path && method == "HEAD") {
                    val digest = url.pathSegments.last()
                    return response(if (blobs.containsKey(digest)) 200 else 404)
                }
                if ("/manifests/" in path) {
                    val ref = url.pathSegments.last()
                    if (method == "HEAD") {
                        val digest = tags[ref] ?: if (manifests.containsKey(ref)) ref else return response(404)
                        return response(200).newBuilder().addHeader("Docker-Content-Digest", verificationDigest ?: digest).build()
                    }
                    if (method == "PUT") {
                        val bytes = requireNotNull(request.body).toByteArray()
                        val digest = GhcrClient.sha256(bytes)
                        val manifest = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                        val type = request.headers["Content-Type"]?.substringBefore(';')
                        val declaredType = manifest["mediaType"]?.jsonPrimitive?.content
                        if (declaredType != null && declaredType != type) return response(400)
                        if (declaredType == null && type?.startsWith("application/vnd.docker.") == true) return response(400)
                        for (child in manifest["manifests"]?.jsonArray.orEmpty()) {
                            if (!manifests.containsKey(child.jsonObject.getValue("digest").jsonPrimitive.content)) return response(400)
                        }
                        for (blob in manifest["layers"]?.jsonArray.orEmpty() + listOfNotNull(manifest["config"])) {
                            val descriptor = blob.jsonObject
                            val external = descriptor["mediaType"]?.jsonPrimitive?.content in setOf(
                                "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip",
                                "application/vnd.oci.image.layer.nondistributable.v1.tar",
                                "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip",
                                "application/vnd.oci.image.layer.nondistributable.v1.tar+zstd",
                            ) && !descriptor["urls"]?.jsonArray.isNullOrEmpty()
                            if (external && blob != manifest["config"]) continue
                            if (!blobs.containsKey(blob.jsonObject.getValue("digest").jsonPrimitive.content)) return response(400)
                        }
                        if (!ref.contains(':')) {
                            if (tagFailures > 0) {
                                tagFailures--; return response(503)
                            }
                            tags[ref] = digest
                        } else if (ref != digest) return response(400)
                        manifests[digest] = bytes
                        return response(201).newBuilder().addHeader("Docker-Content-Digest", digest).build()
                    }
                }
                return response(404)
            }
        }
        server.start()
    }

    private fun response(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()
    override fun close() = server.close()
}

/** Small image graph with shared content and two platform manifests. */
internal class ImageFixture {
    val bytes = linkedMapOf<String, ByteArray>()
    val config = store("""{"architecture":"amd64","os":"linux"}""".toByteArray())
    val layer = store("layer bytes".toByteArray())
    val manifest = store(image(config, layer).toByteArray())
    val otherConfig = store("""{"architecture":"arm64","os":"linux"}""".toByteArray())
    val otherManifest = store(image(otherConfig, layer).toByteArray())
    val index = store(
        """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
        ${descriptor(manifest, "application/vnd.oci.image.manifest.v1+json")},
        ${descriptor(otherManifest, "application/vnd.oci.image.manifest.v1+json")}
    ]}""".trimIndent().toByteArray()
    )

    fun store(value: ByteArray): String = GhcrClient.sha256(value).also { bytes[it] = value }

    fun withoutMediaType(digest: String): String = store(
        JsonObject(Json.parseToJsonElement(bytes.getValue(digest).decodeToString()).jsonObject - "mediaType")
            .toString().toByteArray()
    )

    fun withLayer(mediaType: String, urls: List<String> = emptyList(), manifestType: String = "application/vnd.oci.image.manifest.v1+json"): String {
        val descriptor = buildJsonObject {
            put("digest", layer)
            put("size", bytes.getValue(layer).size)
            put("mediaType", mediaType)
            if (urls.isNotEmpty()) put("urls", JsonArray(urls.map(::JsonPrimitive)))
        }
        val original = Json.parseToJsonElement(bytes.getValue(manifest).decodeToString()).jsonObject
        val config = if (manifestType == "application/vnd.docker.distribution.manifest.v2+json")
            JsonObject(original.getValue("config").jsonObject +
                    ("mediaType" to JsonPrimitive("application/vnd.docker.container.image.v1+json")))
        else original.getValue("config")
        return store(JsonObject(original + mapOf(
            "mediaType" to JsonPrimitive(manifestType), "config" to config, "layers" to JsonArray(listOf(descriptor)),
        )).toString().toByteArray())
    }

    private fun image(config: String, layer: String) = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
        "config":${descriptor(config, "application/vnd.oci.image.config.v1+json")},
        "layers":[${descriptor(layer, "application/vnd.oci.image.layer.v1.tar")}]}""".trimIndent()

    private fun descriptor(digest: String, type: String) = """{"digest":"$digest","size":${bytes.getValue(digest).size},"mediaType":"$type"}"""
}
