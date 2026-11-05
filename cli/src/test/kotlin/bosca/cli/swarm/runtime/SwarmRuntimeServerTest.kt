package bosca.cli.swarm.runtime

import bosca.security.encryption.EncryptionServiceImpl
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.net.URI
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SwarmRuntimeServerTest {
    @Test fun `controller authenticates before observing a runtime and keeps invalid requests out of Docker`(): Unit = runBlocking {
        val observations = AtomicInteger()
        val writes = AtomicInteger()
        val engine = object : SwarmEngine {
            override suspend fun request(method: String, path: String, body: JsonElement?, headers: Map<String, String>): SwarmEngineResponse {
                if (method != "GET") writes.incrementAndGet()
                return if (path.startsWith("/services/")) { observations.incrementAndGet(); SwarmEngineResponse(404, JsonNull) }
                else SwarmEngineResponse(200, JsonArray(emptyList()))
            }
        }
        val token = "private-controller-fixture-credential"
        val port = ServerSocket(0).use { it.localPort }
        val application = BoscaApplication(ApplicationConfig.load("security: {encryption: {key: isolated-test-key}}".byteInputStream()), ownsServices = false)
        val server = swarmRuntimeServer(SwarmRuntimeService(engine, EncryptionServiceImpl(application), SwarmRuntimeConfiguration(emptyMap()), Json), Json, token, "127.0.0.1", port).start()
        val client = HttpClient.newHttpClient()
        fun request(path: String, authorization: String? = null, body: String? = null): Int {
            val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            authorization?.let { builder.header("Authorization", it) }
            if (body != null) builder.PUT(HttpRequest.BodyPublishers.ofString(body))
            return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        }
        try {
            val path = "/v1/runtimes/${UUID.random()}"
            assertEquals(200, request("/healthz"))
            assertEquals(401, request(path))
            assertEquals(401, request(path, token))
            assertEquals(401, request(path, "Bearer wrong"))
            assertEquals(0, observations.get())
            assertEquals(404, request(path, "Bearer $token"))
            assertEquals(1, observations.get())
            assertEquals(400, request(path, "Bearer $token", "{invalid"))
            assertEquals(0, writes.get())
        } finally { server.stop(100, 1000) }
    }
}
