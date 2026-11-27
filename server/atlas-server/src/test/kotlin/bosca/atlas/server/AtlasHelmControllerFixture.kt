package bosca.atlas.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Deterministic controller HTTP boundary; WorkOps and the Helm adapter remain the production services. */
internal class AtlasHelmControllerFixture : AutoCloseable {
    private val calls = ConcurrentLinkedQueue<JsonObject>()
    private val failNext = AtomicBoolean(false)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            var code = 200
            val response = when {
                path == "/fixture/requests" && exchange.requestMethod == "GET" -> JsonArray(calls.toList()).toString()
                path == "/fixture/fail-next" && exchange.requestMethod == "POST" -> {
                    failNext.set(true)
                    "{}"
                }
                path.matches(Regex("/clusters/[^/]+/helm/rollback")) && exchange.requestMethod == "POST" -> {
                    val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    val token = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
                    val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(token.split('.')[1]).decodeToString()).jsonObject
                    calls.add(buildJsonObject {
                        put("path", path)
                        put("request", request)
                        put("subject", claims.getValue("sub"))
                    })
                    if (failNext.getAndSet(false)) {
                        code = 503
                        "Controller fixture unavailable"
                    } else buildJsonObject {
                        put("id", "release-fixture")
                        put("name", request.getValue("name"))
                        put("namespace", request.getValue("namespace"))
                        put("chart", "fixture")
                        put("chartVersion", "1.0.0")
                        put("appVersion", "1.0.0")
                        put("revision", 43)
                        put("status", "DEPLOYED")
                        put("updated", "Fixture rollback")
                        put("installed", "Fixture install")
                        put("repo", "fixture")
                        put("repoUrl", "")
                        put("description", "Isolated controller response")
                    }.toString()
                }
                else -> {
                    code = 404
                    "Unexpected controller fixture request"
                }
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
