@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.configuration

import bosca.di.ProviderRegistry
import bosca.di.providerMissing
import bosca.di.provides
import bosca.graphql.GraphQLConnectionInitAuthenticator
import bosca.graphql.GraphQLRequest
import bosca.graphql.GraphQLService
import bosca.graphql.scalars.UploadedFile
import bosca.security.service.AuthenticationProviders
import bosca.serialization.JsonContent
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.content.MultiPartData
import bosca.server.content.PartData
import bosca.server.routing.RoutingContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.netty.handler.codec.http.multipart.FileUpload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphQLModuleRouteTest {

    private lateinit var application: BoscaApplication
    private lateinit var service: GraphQLService
    private lateinit var content: JsonContent<JsonElement>

    @BeforeTest
    fun setup() = runTest {
        ProviderRegistry.clear()
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        service = mockk(relaxed = true)
        content = mockk(relaxed = true)
        provides<AuthenticationProviders> { AuthenticationProviders(arrayOf("jwt")) }
        providerMissing<GraphQLConnectionInitAuthenticator>()
        coEvery { service.get(any(), any(), any(), any()) } returns content
        coEvery { service.post(any(), any()) } returns content
        coEvery { service.post(any(), any(), any()) } returns content
        application.install(GraphQLModule(service))
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun call(
        query: Map<String, String> = emptyMap(),
        contentType: ContentType? = null,
        body: String = "",
        multipart: MultiPartData? = null,
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters.fromSingleValueMap(query)
        every { request.contentType() } returns contentType
        coEvery { request.bodyText() } returns body
        if (multipart != null) coEvery { request.receiveMultipart() } returns multipart
        val response = mockk<ServerResponse>(relaxed = true)
        every { response.isCommitted } returns false
        return ServerCall(request, response, Parameters.Empty, application)
    }

    private suspend fun execute(method: HttpMethod, path: String, call: ServerCall) {
        val route = assertNotNull(application.router.resolve(method, path))
        route.handler(RoutingContext(call, application))
    }

    @Test
    fun `GET executes query parameters including variables and extensions`() = runTest {
        val call = call(
            query = mapOf(
                "query" to "query Named { value }",
                "variables" to "{\"id\":\"one\"}",
                "extensions" to "{\"trace\":true}",
            ),
        )

        execute(HttpMethod.Get, "/graphql", call)

        coVerify {
            service.get(
                call,
                "query Named { value }",
                match { it.get("id")?.toString() == "\"one\"" },
                match { it.get("trace")?.toString() == "true" },
            )
        }
        coVerify { content.writeTo(call, HttpStatusCode.OK) }
    }

    @Test
    fun `GET without query receives body or falls back to query parameter metadata`() = runTest {
        var call = call(body = "{\"query\":\"query { value }\",\"operationName\":\"Body\"}")
        execute(HttpMethod.Get, "/graphql", call)
        coVerify { service.post(call, match { it.operationName == "Body" }) }

        call = call(
            query = mapOf(
                "variables" to "{\"id\":1}",
                "extensions" to "{\"persistedQuery\":{\"sha256Hash\":\"hash\"}}",
            ),
        )
        coEvery { call.request.bodyText() } throws IllegalStateException("no body")
        execute(HttpMethod.Get, "/graphql", call)
        coVerify {
            service.post(
                call,
                match { it.query == null && it.variables?.get("id")?.toString() == "1" && it.extensions != null },
            )
        }
    }

    @Test
    fun `POST executes ordinary JSON body`() = runTest {
        val call = call(
            contentType = ContentType.Application.Json,
            body = "{\"query\":\"mutation { update }\",\"operationName\":\"Update\"}",
        )

        execute(HttpMethod.Post, "/graphql", call)

        coVerify { service.post(call, match { it.operationName == "Update" }) }
        coVerify { content.writeTo(call, HttpStatusCode.OK) }
    }

    @Test
    fun `multipart POST maps uploaded files into nested variables and ignores missing file keys`() = runTest {
        val upload = mockk<FileUpload>(relaxed = true)
        every { upload.name } returns "0"
        every { upload.filename } returns "avatar.png"
        every { upload.contentType } returns "image/png"
        every { upload.isInMemory } returns true
        every { upload.get() } returns byteArrayOf(1, 2, 3)
        val ignored = PartData.UploadedFileItem(UploadedFile("ignored", "text/plain", byteArrayOf().inputStream()))
        val multipart = MultiPartData(
            listOf(
                PartData.FormItem("operations", "{\"query\":\"mutation Upload(${ '$' }file: Upload) { upload(file: ${ '$' }file) }\",\"variables\":{\"input\":{\"avatar\":null}}}"),
                PartData.FormItem("map", "{\"0\":[\"variables.input.avatar\"],\"missing\":[\"variables.missing\"]}"),
                PartData.FormItem("unrelated", "value"),
                PartData.FileUploadItem(upload),
                ignored,
            ),
        )
        val call = call(contentType = ContentType("multipart", "form-data"), multipart = multipart)
        val variables = slot<Map<String, Any?>>()

        execute(HttpMethod.Post, "/graphql", call)

        coVerify { service.post(call, any<GraphQLRequest>(), capture(variables)) }
        val input = assertIs<Map<*, *>>(variables.captured["input"])
        val uploaded = assertIs<UploadedFile>(input["avatar"])
        assertEquals("avatar.png", uploaded.name)
        assertEquals("image/png", uploaded.contentType)
        assertEquals(listOf<Byte>(1, 2, 3), uploaded.inputStream.readAllBytes().toList())
    }

    @Test
    fun `multipart POST requires operations and permits absent map`() = runTest {
        var call = call(
            contentType = ContentType("multipart", "form-data"),
            multipart = MultiPartData(listOf(PartData.FormItem("map", "{}"))),
        )
        assertFailsWith<IllegalArgumentException> { execute(HttpMethod.Post, "/graphql", call) }

        call = call(
            contentType = ContentType("multipart", "form-data"),
            multipart = MultiPartData(listOf(PartData.FormItem("operations", "{\"query\":\"query { value }\"}"))),
        )
        execute(HttpMethod.Post, "/graphql", call)
        coVerify { service.post(call, any<GraphQLRequest>(), emptyMap()) }
    }

    @Test
    fun `GraphiQL serves bundled UI only when introspection is enabled`() = runTest {
        every { service.isIntrospectionEnabled() } returns false
        var call = call()
        execute(HttpMethod.Get, "/graphiql", call)
        verify { call.response.respondText("", ContentType.Text.Plain, HttpStatusCode.NotFound) }

        every { service.isIntrospectionEnabled() } returns true
        call = call()
        execute(HttpMethod.Get, "/graphiql", call)
        verify { call.response.respondBytes(match { it.isNotEmpty() }, ContentType.Text.Html, HttpStatusCode.OK) }
    }

    @Test
    fun `install wires optional connection init authenticator`() = runTest {
        ProviderRegistry.clear()
        val authenticator = mockk<GraphQLConnectionInitAuthenticator>()
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<GraphQLConnectionInitAuthenticator> { authenticator }
        val app = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        app.install(GraphQLModule(service))

        assertNotNull(app.router.resolveWebSocket("/graphqlws"))
        assertTrue(app.modules.containsKey(GraphQLModule::class))
    }
}
