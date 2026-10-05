@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.graphql

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.schema.GraphQLSchema
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.ResolverContext
import bosca.security.service.AuthenticationContext
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FieldFetchersTest {

    private val tracer = GlobalOpenTelemetry.getTracer("FieldFetchersTest")
    private val authentication = AuthenticationContext(null, null)
    private val schema = GraphQLSchema.fromSdl("type Query { value: String }")

    private fun context(includeAuthentication: Boolean = true): ResolverContext = ResolverContext(
        source = "source",
        arguments = emptyMap(),
        fieldName = "value",
        parentType = "Query",
        schema = schema,
        context = if (includeAuthentication) {
            GraphQLContext(mapOf("authenticationContext" to authentication))
        } else {
            GraphQLContext.EMPTY
        },
    )

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `property fetcher invokes block under a span and requires authentication context`() = runTest {
        val fetcher = PropertyDataFetcher("value", tracer) { environment, auth ->
            "${environment.sourceAs<String>()}:${auth === authentication}"
        }
        assertEquals("source:true", fetcher.resolve(context()))
        assertFailsWith<IllegalStateException> { fetcher.resolve(context(false)) }
    }

    @Test
    fun `flow fetcher returns block flow and requires authentication context`() = runTest {
        val fetcher = FlowDataFetcher("value", tracer) { environment, auth ->
            flowOf(environment.sourceAs<String>()!!, (auth === authentication).toString())
        }
        assertEquals(listOf("source", "true"), fetcher.resolve(context()).toList())
        assertFailsWith<IllegalStateException> { fetcher.resolve(context(false)) }
    }

    @Test
    fun `suspend fetcher establishes connection manager and requires authentication context`() = runTest {
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        val fetcher = SuspendDataFetcher("value", tracer) { environment, auth ->
            assertEquals(authentication, auth)
            assertEquals("source", environment.sourceAs<String>())
            bosca.db.connection()
            "resolved"
        }
        assertEquals("resolved", fetcher.resolve(context()))
        assertFailsWith<IllegalStateException> { fetcher.resolve(context(false)) }
    }
}
