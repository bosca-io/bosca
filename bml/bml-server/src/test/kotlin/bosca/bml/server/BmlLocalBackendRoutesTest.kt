package bosca.bml.server

import bosca.bml.graphql.GraphQLBackend
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.GraphQLClientFactory
import bosca.bml.project.CompiledProject
import bosca.bml.render.*
import bosca.server.BoscaApplication
import bosca.server.HttpMethod
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import bosca.server.routing.RoutingContext
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

/** Real BML routes bind the host backend to each request and retain it across renderer reloads. */
class BmlLocalBackendRoutesTest {
    @Test
    fun `an explicit HTTP backend also leaves browser and OAuth routes with the host`() = runTest {
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        BmlServer(CompiledProject(name = "host-routes", version = "1"), emptyList())
            .withGraphQLBackend(GraphQLClientFactory("http://127.0.0.1:1/graphql"))
            .install(application)
        val graphql: suspend RoutingContext.() -> Unit = { call.respond("host GraphQL") }
        val oauth: suspend RoutingContext.() -> Unit = { call.respond("host OAuth") }
        application.router.post("/graphql", graphql)
        application.router.get("/oauth2/google/login", oauth)
        assertSame(
            graphql,
            requireNotNull(application.router.resolve(HttpMethod.Post, "/graphql")).handler,
        )
        assertSame(
            oauth,
            requireNotNull(application.router.resolve(HttpMethod.Get, "/oauth2/google/login"))
                .handler,
        )
    }

    @Test
    fun `pages actions contracts fragments and browser requests use the host backend after reload`() {
        val backend =
            object : GraphQLBackend {
                override fun prepareRequest(
                    call: ServerCall,
                    installationId: String?,
                    analyticsSessionId: String?,
                ) {
                    call.attributes["local.identity"] = "$installationId|$analyticsSessionId"
                }

                override fun forRequest(
                    call: ServerCall,
                    token: String?,
                    installationId: String?,
                    analyticsSessionId: String?,
                ): GraphQLClient {
                    val defaultToken = token
                    return object : GraphQLClient {
                        override suspend fun execute(
                            query: String,
                            variables: JsonObject?,
                            operationName: String?,
                            token: String?,
                        ): JsonElement =
                            JsonPrimitive(
                                "local|${token ?: defaultToken}|$installationId|$analyticsSessionId"
                            )
                    }
                }
            }
        val dispatcher =
            object : BmlIslandActionDispatcher {
                override val stateKey = "probe"
                override val serverScoped = false

                override suspend fun dispatch(
                    ctx: RenderContext,
                    method: String,
                    state: String,
                    args: JsonArray,
                    instanceKey: String,
                ) = IslandActionResult("{}", ctx.gql.execute("{ probe }").toString())
            }
        fun server(label: String) =
            BmlServer(
                CompiledProject(name = "local-test", version = "1"),
                listOf(
                    object : BmlPageRenderer {
                        override val route = "/probe"

                        override suspend fun render(ctx: RenderContext) {
                            ctx.writer.markup(
                                "<html><body>$label:${ctx.gql.execute("{ probe }")}</body></html>"
                            )
                        }
                    }
                ),
                graphqlEndpoint = "http://127.0.0.1:1/graphql",
                authCookiePrefix = "_local",
                dev = true,
                islandDispatchers = mapOf("/probe" to mapOf("probe" to dispatcher)),
                componentRenderers =
                    mapOf(
                        "probe" to
                            BmlComponentRenderer { ctx, _, _ ->
                                ctx.writer.markup(ctx.gql.execute("{ probe }").toString())
                            }
                    ),
                contracts =
                    listOf(
                        object : BmlContractDispatcher {
                            override val name = "Probe"
                            override val methods = listOf("read")

                            override suspend fun dispatch(
                                gql: GraphQLClient?,
                                method: String,
                                argsJson: String,
                            ) = requireNotNull(gql).execute("{ probe }").toString()
                        }
                    ),
            )
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        application.router.post("/graphql") {
            call.respond(call.attributes["local.identity"].toString())
        }
        val bml = server("initial").withGraphQLBackend(backend)
        bml.install(application)
        val port = ServerSocket(0).use { it.localPort }
        val engine = NettyServerEngine(application, port)
        thread(isDaemon = true) { engine.start() }
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        fun request(path: String, body: String? = null): HttpResponse<String> {
            val request =
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Cookie", "_local=caller; bml_iid=installation; bml_asid=session")
            body?.let {
                request
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(it))
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline && runCatching { request("/probe") }.isFailure) Thread
            .sleep(25)
        fun verify(label: String) {
            val page = request("/probe")
            assertEquals(200, page.statusCode(), page.body())
            assertTrue("$label:" in page.body(), page.body())
            assertTrue("local|caller|installation|session" in page.body(), page.body())
            for ((path, body) in
                listOf(
                    "/_bml/action" to
                        """{"page":"/probe","stateKey":"probe","method":"read","state":"{}"}""",
                    "/_bml/render/probe" to "{}",
                    "/_bml/contract/Probe/read" to "[]",
                )) {
                val response = request(path, body)
                assertEquals(200, response.statusCode(), response.body())
                assertTrue("local|caller|installation|session" in response.body(), response.body())
            }
            val browser = request("/graphql", "{}")
            assertEquals(200, browser.statusCode(), browser.body())
            assertTrue("installation|session" in browser.body(), browser.body())
        }
        verify("initial")
        val rollback = bml.hotSwapFrom(server("replacement"))
        verify("replacement")
        rollback()
        verify("initial")
    }
}
