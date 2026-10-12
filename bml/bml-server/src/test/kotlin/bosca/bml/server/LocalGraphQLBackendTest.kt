package bosca.bml.server

import bosca.bml.graphql.GraphQLException
import bosca.graphql.GraphQLService
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class LocalGraphQLBackendTest {
    @Test
    fun `bound operations retain the call variables operation name and verified credentials`() =
        runTest {
            val service = mockk<GraphQLService>()
            val call = mockk<ServerCall>()
            val credentials = mutableListOf<String?>()
            val metadata = mutableListOf<Pair<String?, String?>>()
            val backend =
                LocalGraphQLBackend(
                    service,
                    requestMetadata = { received, installation, session ->
                        assertSame(call, received)
                        metadata.add(installation to session)
                    },
                ) { received, token ->
                    assertSame(call, received)
                    credentials.add(token)
                    token != "rejected"
                }
            val client = backend.forRequest(call, "default", "installation", "session")
            val data = buildJsonObject { put("value", 7) }
            coEvery { service.execute(call, any()) } returns buildJsonObject { put("data", data) }
            val variables = buildJsonObject { put("id", "id") }
            assertEquals(data, client.execute("query Named { value }", variables, "Named"))
            coVerify {
                service.execute(
                    call,
                    match {
                        it.query == "query Named { value }" &&
                            it.variables == variables &&
                            it.operationName == "Named"
                    },
                )
            }
            assertEquals(data, client.execute("{ value }", token = "override"))
            assertFailsWith<GraphQLException> { client.execute("{ value }", token = "rejected") }
            coVerify(exactly = 2) { service.execute(call, any()) }
            assertEquals(listOf<String?>("default", "override", "rejected"), credentials)
            assertEquals(
                listOf<Pair<String?, String?>>(
                    "installation" to "session",
                    "installation" to "session",
                ),
                metadata,
            )
        }

    @Test
    fun `errors and absent data use the existing BML client contract`() = runTest {
        val service = mockk<GraphQLService>()
        val call = mockk<ServerCall>()
        val client =
            LocalGraphQLBackend(service) { _, _ -> true }.forRequest(call, null, null, null)
        coEvery { service.execute(call, any()) } returns
            Json.parseToJsonElement(
                """{"data":{"value":null},"errors":[{"message":"Denied","path":["value"],"extensions":{"code":"FORBIDDEN"}}]}"""
            )
        assertEquals(
            "Denied",
            assertFailsWith<GraphQLException> { client.execute("{ value }") }.message,
        )
        coEvery { service.execute(call, any()) } returns JsonObject(emptyMap())
        assertEquals(JsonNull, client.execute("{ value }"))
    }

    @Test
    fun `cancellation propagates from authentication and execution`() = runTest {
        val service = mockk<GraphQLService>()
        val call = mockk<ServerCall>()
        val cancelled = CancellationException("cancelled")
        val unauthenticated =
            LocalGraphQLBackend(service) { _, _ -> throw cancelled }
                .forRequest(call, null, null, null)
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { unauthenticated.execute("{ value }") },
        )
        val authenticated =
            LocalGraphQLBackend(service) { _, _ -> true }.forRequest(call, null, null, null)
        coEvery { service.execute(call, any()) } throws cancelled
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { authenticated.execute("{ value }") },
        )
    }
}
