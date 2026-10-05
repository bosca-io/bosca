package bosca.graphql

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphQLRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- GraphQLRequest ---

    @Test
    fun `GraphQLRequest optional fields default to null`() {
        val request = GraphQLRequest()
        assertNull(request.query)
        assertNull(request.extensions)
        assertNull(request.operationName)
        assertNull(request.variables)
    }

    @Test
    fun `GraphQLRequest stores all properties`() {
        val vars = JsonObject(mapOf("id" to JsonPrimitive("123")))
        val request = GraphQLRequest(
            query = "{ user(id: \$id) { name } }",
            operationName = "GetUser",
            variables = vars
        )
        assertEquals("{ user(id: \$id) { name } }", request.query)
        assertEquals("GetUser", request.operationName)
        assertEquals(vars, request.variables)
    }

    @Test
    fun `GraphQLRequest round-trips through serialization`() {
        val original = GraphQLRequest(
            query = "{ hello }",
            operationName = "Hello"
        )
        val serialized = json.encodeToString(GraphQLRequest.serializer(), original)
        val deserialized = json.decodeFromString(GraphQLRequest.serializer(), serialized)
        assertEquals(original.query, deserialized.query)
        assertEquals(original.operationName, deserialized.operationName)
    }

    // --- GraphQLSubscriptionRequest ---

    @Test
    fun `GraphQLSubscriptionRequest optional fields default to null`() {
        val request = GraphQLSubscriptionRequest(type = "connection_init")
        assertNull(request.id)
        assertNull(request.payload)
        assertEquals("connection_init", request.type)
    }

    @Test
    fun `GraphQLSubscriptionRequest stores all properties`() {
        val payload = JsonObject(mapOf("query" to JsonPrimitive("{ updates }")))
        val request = GraphQLSubscriptionRequest(
            type = "subscribe", id = "sub-1", payload = payload
        )
        assertEquals("subscribe", request.type)
        assertEquals("sub-1", request.id)
        assertEquals(payload, request.payload)
    }

    // --- GraphQLSubscriptionResponse ---

    @Test
    fun `GraphQLSubscriptionResponse optional fields default to null`() {
        val response = GraphQLSubscriptionResponse(type = "connection_ack")
        assertNull(response.id)
        assertNull(response.payload)
    }

    @Test
    fun `GraphQLSubscriptionResponse stores all properties`() {
        val payload = JsonObject(mapOf("data" to JsonPrimitive("result")))
        val response = GraphQLSubscriptionResponse(
            type = "next", id = "sub-1", payload = payload
        )
        assertEquals("next", response.type)
        assertEquals("sub-1", response.id)
        assertEquals(payload, response.payload)
    }
}
