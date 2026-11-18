package bosca.atlas.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** An owned embedding provider can hold one real indexing task while another is canceled. */
internal class AtlasMeilisearchProvider(private val enqueue: (Int) -> Pair<Int, Int>) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val gates = Array(2) { CountDownLatch(0) }
    private val held = Array(2) { AtomicInteger() }
    val credentials = CopyOnWriteArrayList<String>()
    val canceledTasks = CopyOnWriteArrayList<Int>()
    val origin get() = "http://localhost:${server.address.port}"
    val port get() = server.address.port
    fun remoteUrl(host: Int) = "http://host.testcontainers.internal:$port/embeddings/$host"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val host = (if (path.startsWith("/embeddings/")) path.substringAfterLast('/') else exchange.requestURI.query?.substringAfter("host="))?.toIntOrNull()
            require(host in 0..1) { "Use an owned provider host." }
            val index = requireNotNull(host)
            val body = when {
                path.startsWith("/embeddings/") -> {
                    exchange.requestBody.readAllBytes()
                    val credential = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                    credentials += credential
                    if (credential != "Bearer fixture-embedder-key") {
                        val error = "{\"error\":\"Expected fixture credential\"}".toByteArray()
                        exchange.sendResponseHeaders(401, error.size.toLong()); exchange.responseBody.use { it.write(error) }
                        return@createContext
                    }
                    if (gates[index].count > 0) held[index].incrementAndGet()
                    check(gates[index].await(120, TimeUnit.SECONDS)) { "Embedding fixture was not released" }
                    buildJsonObject { put("embedding", JsonArray(listOf(JsonPrimitive(0.1), JsonPrimitive(0.2)))) }
                }
                path == "/hold" -> { gates[index] = CountDownLatch(1); buildJsonObject { put("held", true) } }
                path == "/release" -> { gates[index].countDown(); buildJsonObject { put("released", true) } }
                path == "/status" -> buildJsonObject { put("held", held[index].get()); put("requests", credentials.size) }
                path == "/enqueue" -> {
                    val (first, target) = enqueue(index); canceledTasks += target
                    buildJsonObject { put("first", first); put("target", target) }
                }
                else -> error("Unexpected fixture path: $path")
            }
            val bytes = body.toString().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }
    override fun close() { gates.forEach { it.countDown() }; server.stop(0); executor.close() }
}
