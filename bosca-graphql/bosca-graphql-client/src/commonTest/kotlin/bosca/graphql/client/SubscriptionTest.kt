package bosca.graphql.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Drives the graphql-transport-ws [WsProtocol] message builders/parser and the subscription bridge. */
class SubscriptionTest {

    @Test
    fun `connectionInit emits the type and optional auth payload`() {
        assertEquals("""{"type":"connection_init"}""", WsProtocol.connectionInit())
        val withAuth = WsProtocol.connectionInit(buildJsonObject { put("token", JsonPrimitive("t")) })
        val obj = GraphQLJson.parseToJsonElement(withAuth).jsonObject
        assertEquals("connection_init", obj.getValue("type").jsonPrimitive.content)
        assertEquals("t", obj.getValue("payload").jsonObject.getValue("token").jsonPrimitive.content)
    }

    @Test
    fun `subscribe emits id and a query payload, with and without variables or operationName`() {
        val full = GraphQLJson.parseToJsonElement(
            WsProtocol.subscribe("1", "subscription S { x }", buildJsonObject { put("a", JsonPrimitive(1)) }, "S"),
        ).jsonObject
        assertEquals("subscribe", full.getValue("type").jsonPrimitive.content)
        assertEquals("1", full.getValue("id").jsonPrimitive.content)
        val payload = full.getValue("payload").jsonObject
        assertEquals("subscription S { x }", payload.getValue("query").jsonPrimitive.content)
        assertEquals("S", payload.getValue("operationName").jsonPrimitive.content)
        assertTrue("a" in payload.getValue("variables").jsonObject)

        val bare = GraphQLJson.parseToJsonElement(WsProtocol.subscribe("2", "subscription { y }", null, null)).jsonObject
        val barePayload = bare.getValue("payload").jsonObject
        assertTrue("operationName" !in barePayload && "variables" !in barePayload)
    }

    @Test
    fun `complete and pong are well-formed`() {
        assertEquals("""{"type":"complete","id":"9"}""", WsProtocol.complete("9"))
        assertEquals("""{"type":"pong"}""", WsProtocol.pong())
    }

    @Test
    fun `parse recognizes every server message kind`() {
        assertIs<WsServerMessage.ConnectionAck>(WsProtocol.parse("""{"type":"connection_ack"}"""))
        val next = assertIs<WsServerMessage.Next>(WsProtocol.parse("""{"type":"next","id":"1","payload":{"data":{"x":1}}}"""))
        assertEquals("1", next.id)
        assertEquals(1, next.response.data!!.jsonObject.getValue("x").jsonPrimitive.content.toInt())
        val err = assertIs<WsServerMessage.Error>(WsProtocol.parse("""{"type":"error","id":"1","payload":[{"message":"boom"}]}"""))
        assertEquals("boom", err.errors.single().message)
        assertEquals("1", assertIs<WsServerMessage.Complete>(WsProtocol.parse("""{"type":"complete","id":"1"}""")).id)
        assertIs<WsServerMessage.Ping>(WsProtocol.parse("""{"type":"ping"}"""))
        assertIs<WsServerMessage.Pong>(WsProtocol.parse("""{"type":"pong"}"""))
        assertEquals("weird", assertIs<WsServerMessage.Unknown>(WsProtocol.parse("""{"type":"weird"}""")).type)
        assertEquals("", assertIs<WsServerMessage.Unknown>(WsProtocol.parse("""{"noType":true}""")).type)
    }

    private object Sub : BoscaOperation<Unit, JsonObject> {
        override val operationName: String = "OnTick"
        override val document: String = "subscription OnTick { tick }"
        override fun encodeVariables(variables: Unit): JsonObject = JsonObject(emptyMap())
        override fun decodeData(data: JsonElement): JsonObject = data.jsonObject
    }

    private class FakeSubClient(private val frames: List<GraphQLResponse>) : GraphQLSubscriptionClient {
        override fun subscribe(document: String, variables: JsonObject?, operationName: String?): Flow<GraphQLResponse> =
            flowOf(*frames.toTypedArray())
    }

    @Test
    fun `the subscription bridge decodes each frame to typed data`() = runTest {
        val frames = listOf(
            GraphQLResponse(data = buildJsonObject { put("tick", JsonPrimitive(1)) }),
            GraphQLResponse(data = buildJsonObject { put("tick", JsonPrimitive(2)) }),
        )
        val ticks = FakeSubClient(frames).subscribe(Sub, Unit).toList()
        assertEquals(listOf(1, 2), ticks.map { it.getValue("tick").jsonPrimitive.content.toInt() })
    }

    @Test
    fun `the subscription bridge throws on an error frame`() = runTest {
        val client = FakeSubClient(listOf(GraphQLResponse(errors = listOf(GraphQLError("boom")))))
        assertFailsWith<GraphQLClientException> { client.subscribe(Sub, Unit).toList() }
    }

    @Test
    fun `the subscription bridge throws on a frame with no data`() = runTest {
        val client = FakeSubClient(listOf(GraphQLResponse(data = null, errors = null)))
        assertFailsWith<GraphQLClientException> { client.subscribe(Sub, Unit).toList() }
    }
}
