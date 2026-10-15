package bosca.git.ui

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.*

/** Runs only isolated fixture reads and reports initial HTML latency and shared-pool reuse. */
object GitUiPageLoadingProbe {
    @JvmStatic
    fun main(args: Array<String>) {

    GitUiRoutesTest.start()
    val relay = HttpClient.newHttpClient()
    val delayMs = AtomicInteger(0)
    val active = AtomicInteger(0)
    val peak = AtomicInteger(0)
    val calls = CopyOnWriteArrayList<String>()
    val connections = CopyOnWriteArrayList<String>()
    val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    proxy.executor = executor
    proxy.createContext("/graphql") { exchange ->
        val n = active.incrementAndGet()
        peak.updateAndGet { maxOf(it, n) }
        try {
            val body = exchange.requestBody.readAllBytes()
            val input = Json.parseToJsonElement(body.decodeToString()).jsonObject
            calls.add(input["operationName"]?.jsonPrimitive?.content.orEmpty())
            connections.add(exchange.remoteAddress.toString())
            Thread.sleep(delayMs.get().toLong())
            val request = HttpRequest.newBuilder(URI("http://localhost:${GitUiRoutesTest.browserFixtureApiPort()}/graphql"))
                .header("Content-Type", "application/json")
                .header("Authorization", exchange.requestHeaders.getFirst("Authorization") ?: "Bearer ui-test-token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
            val response = relay.send(request, HttpResponse.BodyHandlers.ofByteArray())
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(response.statusCode(), response.body().size.toLong())
            exchange.responseBody.use { it.write(response.body()) }
        } finally {
            active.decrementAndGet()
            exchange.close()
        }
    }
    proxy.start()
    val port = ServerSocket(0).use { it.localPort }
    val config = "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n"
    val app = BoscaApplication(ApplicationConfig.load(config.byteInputStream()))
    installGitUi(app, "http://127.0.0.1:${proxy.address.port}/graphql", "_git_test")
    app.freezeMiddleware()
    val engine = NettyServerEngine(app, port)
    thread(isDaemon = true, name = "bml-data-probe") { engine.start() }
    val client = HttpClient.newHttpClient()
    fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Cookie", "_git_test=ui-test-token").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    fun post(path: String, body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Cookie", "_git_test=ui-test-token")
            .header("Origin", "http://localhost:$port")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    try {
        var ready = false
        repeat(100) { if (!ready) { try { get("/login"); ready = true } catch (_: java.io.IOException) { Thread.sleep(50) } } }
        check(ready)
        val paths = graphqlPageAuditPaths
        for (path in paths) repeat(2) { check(get(path).statusCode() == 200) { "Warmup failed: $path" } }
        for (latency in listOf(0, 50)) {
            delayMs.set(latency)
            for (path in paths) {
                val durations = mutableListOf<Double>()
                var count = 0
                var sequence: List<String> = emptyList()
                calls.clear(); connections.clear(); peak.set(0)
                repeat(3) {
                    val before = calls.size
                    val start = System.nanoTime()
                    val response = get(path)
                    durations.add((System.nanoTime() - start) / 1_000_000.0)
                    check(response.statusCode() == 200) { "Failed ${response.statusCode()}: $path" }
                    count = calls.size - before
                    sequence = calls.drop(before)
                }
                println("PROBE latency=$latency path=$path medianMs=${"%.1f".format(durations.sorted()[1])} calls=$count peak=${peak.get()} connectionsAcross3Renders=${connections.distinct().size} sequence=${sequence.joinToString(",")}")
            }
            val shell = get("/search?repo=repo&q=snippet").body()
            val marker = Regex("""<script type="application/json" data-bml-state-key="(git-search.model:search[^"]*)"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(shell) ?: error("No search state")
            val payload = buildJsonObject {
                put("page", "/search"); put("path", "/search")
                put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2])
                put("method", "search")
                put("args", JsonArray(listOf("repo", "image", "content", "main").map(::JsonPrimitive)))
            }.toString()
            calls.clear(); connections.clear(); peak.set(0)
            val actionTimes = (1..3).map {
                val start = System.nanoTime()
                check(post("/_bml/action", payload).statusCode() == 200)
                (System.nanoTime() - start) / 1_000_000.0
            }
            println("PROBE latency=$latency path=search-action medianMs=${"%.1f".format(actionTimes.sorted()[1])} calls=${calls.size/3} peak=${peak.get()} sequence=${calls.take(calls.size/3).joinToString(",")}")
        }
        calls.clear(); connections.clear(); peak.set(0)
        val started = System.nanoTime()
        val callers = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val work = (1..8).map { callers.submit<Int> { get("/repositories/repo?tab=branches").statusCode() } }
            check(work.all { it.get() == 200 })
            println("PROBE concurrentRenders=8 latency=50 elapsedMs=${"%.1f".format((System.nanoTime()-started)/1_000_000.0)} calls=${calls.size} peak=${peak.get()} connections=${connections.distinct().size}")
            val seenConnections = connections.toMutableSet()
            for (burst in 2..3) {
                Thread.sleep(250)
                calls.clear(); connections.clear(); peak.set(0)
                val began = System.nanoTime()
                val next = (1..8).map { callers.submit<Int> { get("/repositories/repo?tab=branches").statusCode() } }
                check(next.all { it.get() == 200 })
                val ports = connections.toSet()
                println("PROBE burst=$burst concurrentRenders=8 elapsedMs=${"%.1f".format((System.nanoTime()-began)/1_000_000.0)} connections=${ports.size} newConnections=${ports.count { it !in seenConnections }}")
                seenConnections.addAll(ports)
            }
        } finally { callers.close() }
    } finally {
        engine.stopWithoutHalting()
        proxy.stop(0)
        executor.close()
        GitUiRoutesTest.stop()
    }
    kotlin.system.exitProcess(0)
}

}
