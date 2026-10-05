@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.graphql

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.persistedqueries.PersistedQuery
import bosca.graphql.persistedqueries.PersistedQueryRepository
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.observability.ErrorCapture
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object ExecutionSchemaRoot : SchemaRoot {
    override val query: QueryRoot = object : QueryRoot {}
    override val mutation: MutationRoot = object : MutationRoot {}
    override val subscription: SubscriptionRoot = object : SubscriptionRoot {}
}

private class ExecutionGraphQLService(
    introspectionEnabled: Boolean = true,
) : GraphQLService(ExecutionSchemaRoot, introspectionEnabled) {
    override val dispatchersRegistrar: DispatchersRegistrar = object : DispatchersRegistrar {
        override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
    }

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        builder.type("Query") {
            field("echo") { context -> context.getArgument<String>("value") ?: "ok" }
        }
    }
}

class GraphQLServiceExecutionTest {
    private lateinit var application: BoscaApplication
    private lateinit var pool: ConnectionPool
    private lateinit var repository: PersistedQueryRepository

    @BeforeTest
    fun setUp() = runTest {
        ProviderRegistry.clear()
        application = application(development = false)
        pool = mockk()
        repository = mockk()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        provides<PersistedQueryRepository> { repository }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("GraphQLServiceExecutionTest") }

        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                scalar UUID
                type Query { echo(value: String): String! }
            """.trimIndent()
        })
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun application(development: Boolean): BoscaApplication = BoscaApplication(
        ApplicationConfig.load(
            """
            bosca:
              server:
                development: $development
            """.trimIndent().byteInputStream(),
        ),
    )

    private fun call(app: BoscaApplication = application): ServerCall = ServerCall(
        mockk(relaxed = true),
        mockk(relaxed = true),
        application = app,
    )

    private fun value(result: JsonObject): String =
        result.getValue("data").jsonObject.getValue("echo").jsonPrimitive.content

    @Test
    fun `all query entry points execute and expose schema state`() = runTest {
        val service = ExecutionGraphQLService()
        val call = call()
        val variables = buildJsonObject { put("value", "variable") }

        assertFalse(service.isReady())
        assertTrue(service.isIntrospectionEnabled())
        assertEquals("Query", service.getSchema().queryTypeName)
        assertTrue(service.isReady())

        assertEquals("ok", value(service.get(call, "{ echo }", null, null).asJsonElement().jsonObject))
        assertEquals(
            "variable",
            value(
                service.get(
                    call,
                    "query Echo(\$value: String) { echo(value: \$value) }",
                    variables,
                    null,
                ).asJsonElement().jsonObject,
            ),
        )
        assertEquals(
            "variable",
            value(
                service.getAsJsonElement(
                    call,
                    "Echo",
                    "query Echo(\$value: String) { echo(value: \$value) }",
                    variables,
                    null,
                ).jsonObject,
            ),
        )
        assertEquals(
            "ok",
            value(service.getAsJsonElement(call, "Echo", "query Echo { echo }", null, null).jsonObject),
        )
        assertEquals(
            "variable",
            value(
                service.post(
                    call,
                    GraphQLRequest(
                        query = "query Echo(\$value: String) { echo(value: \$value) }",
                        operationName = "Echo",
                        variables = variables,
                    ),
                ).asJsonElement().jsonObject,
            ),
        )
        assertEquals(
            "override",
            value(
                service.post(
                    call,
                    GraphQLRequest(query = "query Echo(\$value: String) { echo(value: \$value) }"),
                    mapOf("value" to "override"),
                ).asJsonElement().jsonObject,
            ),
        )
        assertEquals(
            "direct",
            value(
                service.execute(
                    AuthenticationContext(null, null),
                    GraphQLRequest(
                        query = "query Echo(\$value: String) { echo(value: \$value) }",
                        variables = buildJsonObject { put("value", "direct") },
                    ),
                ).jsonObject,
            ),
        )
    }

    @Test
    fun `persisted queries report malformed missing and cached states`() = runTest {
        val service = ExecutionGraphQLService()
        val authentication = AuthenticationContext(null, null)
        val known = buildJsonObject {
            put("persistedQuery", buildJsonObject { put("sha256Hash", "known") })
        }
        val unknown = buildJsonObject {
            put("persistedQuery", buildJsonObject { put("sha256Hash", "unknown") })
        }
        coEvery { repository.findBySha256("known") } returns
            listOf(PersistedQuery(query = "{ echo }", sha256 = "known"))
        coEvery { repository.findBySha256("unknown") } returns emptyList()

        assertTrue("errors" in service.execute(authentication, GraphQLRequest()).jsonObject)
        assertTrue(
            "errors" in service.execute(
                authentication,
                GraphQLRequest(extensions = buildJsonObject { put("persistedQuery", JsonPrimitive("bad")) }),
            ).jsonObject,
        )
        assertTrue(
            "errors" in service.execute(
                authentication,
                GraphQLRequest(extensions = buildJsonObject { put("persistedQuery", JsonObject(emptyMap())) }),
            ).jsonObject,
        )
        assertTrue(
            "errors" in service.execute(
                authentication,
                GraphQLRequest(extensions = buildJsonObject {
                    put("persistedQuery", buildJsonObject { put("sha256Hash", JsonNull) })
                }),
            ).jsonObject,
        )
        coVerify(exactly = 0) { repository.findBySha256("null") }
        val missing = service.execute(authentication, GraphQLRequest(extensions = unknown)).jsonObject
        assertContains(missing.getValue("errors").toString(), "PERSISTED_QUERY_NOT_FOUND")

        assertEquals("ok", value(service.execute(authentication, GraphQLRequest(extensions = known)).jsonObject))
        assertEquals("ok", value(service.execute(authentication, GraphQLRequest(extensions = known)).jsonObject))
        coVerify(exactly = 1) { repository.findBySha256("known") }

        service.clearPersistedQueries()
        assertEquals("ok", value(service.execute(authentication, GraphQLRequest(extensions = known)).jsonObject))
        coVerify(exactly = 2) { repository.findBySha256("known") }
    }

    @Test
    fun `introspection requires development mode or administrator membership`() = runTest {
        val service = ExecutionGraphQLService(introspectionEnabled = true)
        val query = GraphQLRequest(query = "{ __schema { queryType { name } } }")

        assertTrue("errors" in service.execute(AuthenticationContext(null, null), query).jsonObject)

        val administrator = ImpersonatedAuthenticationContext(
            Principal(),
            listOf(Group(name = "administrators", description = "", type = GroupType.SYSTEM)),
        )
        assertFalse("errors" in service.execute(administrator, query).jsonObject)

        val developmentCall = call(application(development = true))
        assertFalse(
            "errors" in service.post(developmentCall, query).asJsonElement().jsonObject,
        )
    }
}
