package bosca.graphql

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class GraphQLModelsTest {

    @Test
    fun `GraphQLRequest equality copy defaults and hashes cover every field`() {
        val variables = JsonObject(mapOf("id" to JsonPrimitive(1)))
        val extensions = JsonObject(mapOf("trace" to JsonPrimitive(true)))
        val base = GraphQLRequest("query { value }", extensions, "Value", variables)
        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertFalse(base.equals(null))
        assertFalse(base.equals("request"))
        listOf(
            base.copy(query = "query { other }"),
            base.copy(extensions = null),
            base.copy(operationName = "Other"),
            base.copy(variables = null),
        ).forEach { assertNotEquals(base, it) }
        GraphQLRequest(query = base.query).hashCode()
        GraphQLRequest(extensions = extensions).hashCode()
        GraphQLRequest(operationName = base.operationName).hashCode()
        GraphQLRequest(variables = variables).hashCode()
    }

    @Test
    fun `subscription request and response cover nullable data class branches`() {
        val payload = JsonObject(mapOf("query" to JsonPrimitive("subscription { x }")))
        val request = GraphQLSubscriptionRequest("subscribe", "one", payload)
        assertEquals(request, request)
        assertEquals(request, request.copy())
        assertFalse(request.equals(null))
        assertFalse(request.equals("request"))
        listOf(
            request.copy(type = "complete"),
            request.copy(id = null),
            request.copy(payload = null),
        ).forEach { assertNotEquals(request, it) }
        GraphQLSubscriptionRequest("subscribe", id = "one").hashCode()
        GraphQLSubscriptionRequest("subscribe", payload = payload).hashCode()

        val response = GraphQLSubscriptionResponse("one", "next", payload)
        assertEquals(response, response)
        assertEquals(response, response.copy())
        assertFalse(response.equals(null))
        assertFalse(response.equals("response"))
        listOf(
            response.copy(id = null),
            response.copy(type = "complete"),
            response.copy(payload = null),
        ).forEach { assertNotEquals(response, it) }
        GraphQLSubscriptionResponse(type = "next", id = "one").hashCode()
        GraphQLSubscriptionResponse(type = "next", payload = payload).hashCode()
    }
}
