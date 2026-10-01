package bosca.bml.benchmarks

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlDeferredRenderer
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.BmlSharedCacheRevision
import bosca.bml.render.RenderContext
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread

/** Loopback latency through the real BML routes and Netty engine. Each JMH fork owns one server. */
@State(Scope.Benchmark)
open class BmlHttpBenchmark {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private lateinit var engine: NettyServerEngine
    internal var port: Int = -1
        private set
    private lateinit var sharedRequest: HttpRequest
    private lateinit var controlRequest: HttpRequest
    private lateinit var conditionalRequest: HttpRequest
    private lateinit var privateRequest: HttpRequest
    private lateinit var deferredRequest: HttpRequest

    @Setup
    open fun setup() {
        port = ServerSocket(0).use { it.localPort }
        val privatePage = object : BmlPageRenderer {
            override val route: String = "/private"
            override suspend fun render(ctx: RenderContext) = BenchmarkFixture.page.render(ctx)
        }
        val fragment = object : BmlDeferredRenderer {
            override val id: String = "benchmark:fragment"
            override val pageRoute: String = "/deferred"
            override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {
                ctx.writer.markup("<span>").text(props["label"]).markup("</span>")
            }
        }
        val deferredPage = object : BmlPageRenderer {
            override val route: String = "/deferred"
            override val deferredRenderers: List<BmlDeferredRenderer> = listOf(fragment)
            override suspend fun render(ctx: RenderContext) {
                ctx.writer.markup("<html><body><div data-bml-deferred=\"benchmark:fragment\"></div></body></html>")
            }
        }
        val server = BmlServer(
            project = CompiledProject("bml-benchmarks", "1"),
            pages = listOf(BenchmarkFixture.page, privatePage, deferredPage),
            port = port,
            dev = false,
            sharedCacheRevisionProvider = { BmlSharedCacheRevision("fixture-v1") },
        )
        val app = BoscaApplication(ApplicationConfig.load(
            "bosca:\n  server:\n    port: $port\n    development: false\n".byteInputStream(),
        ))
        server.install(app)
        val controlContext = RenderContext()
        runBlocking { BenchmarkFixture.page.render(controlContext) }
        val controlBody = controlContext.writer.toString().toByteArray(Charsets.UTF_8)
        app.routing {
            get("/benchmark-control") {
                call.respondBytes(controlBody, ContentType.Text.Html)
            }
        }
        app.freezeMiddleware()
        engine = NettyServerEngine(app, port)
        thread(isDaemon = true, name = "bml-benchmark-server") { engine.start() }
        var ready = false
        for (attempt in 0 until 200) {
            try {
                Socket("localhost", port).use { }
                ready = true
                break
            } catch (_: Exception) {
                Thread.sleep(10)
            }
        }
        check(ready) { "BML benchmark server did not start on port $port" }
        val base = "http://localhost:$port"
        sharedRequest = HttpRequest.newBuilder(URI.create("$base/benchmark")).GET().build()
        controlRequest = HttpRequest.newBuilder(URI.create("$base/benchmark-control")).GET().build()
        privateRequest = HttpRequest.newBuilder(URI.create("$base/private")).GET().build()
        val initial = send(sharedRequest)
        check(initial.statusCode() == 200)
        val etag = initial.headers().firstValue("ETag").orElseThrow()
        conditionalRequest = HttpRequest.newBuilder(URI.create("$base/benchmark"))
            .header("If-None-Match", etag).GET().build()
        deferredRequest = HttpRequest.newBuilder(URI.create("$base/_bml/deferred/benchmark%3Afragment"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                """{"props":{"label":"Bench <&>"},"page":"/deferred","path":"/deferred","query":{},"locale":"en"}""",
            )).build()
        check(send(privateRequest).statusCode() == 200)
        check(send(controlRequest).statusCode() == 200)
        check(send(conditionalRequest).statusCode() == 304)
        check(send(deferredRequest).statusCode() == 200)
    }

    @TearDown
    open fun tearDown() {
        client.close()
        if (::engine.isInitialized) engine.stopWithoutHalting()
    }

    @Benchmark
    open fun staticControl(): Int = bodySize(controlRequest, 200)

    @Benchmark
    open fun sharedPage(): Int = bodySize(sharedRequest, 200)

    @Benchmark
    open fun privatePage(): Int = bodySize(privateRequest, 200)

    @Benchmark
    open fun sharedPageNotModified(): Int = bodySize(conditionalRequest, 304)

    @Benchmark
    open fun deferredFragment(): Int = bodySize(deferredRequest, 200)

    private fun bodySize(request: HttpRequest, expectedStatus: Int): Int {
        val response = send(request)
        check(response.statusCode() == expectedStatus) { "HTTP ${response.statusCode()} from ${request.uri()}" }
        return response.body().size
    }

    private fun send(request: HttpRequest): HttpResponse<ByteArray> =
        client.send(request, HttpResponse.BodyHandlers.ofByteArray())
}
