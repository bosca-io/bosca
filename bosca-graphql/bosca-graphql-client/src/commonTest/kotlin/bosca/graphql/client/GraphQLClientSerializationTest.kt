package bosca.graphql.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the generated `@Serializable` codecs for the response envelope (encode + decode, every field combo). */
class GraphQLClientSerializationTest {

    @Test
    fun `the response envelope round-trips with data and errors`() {
        val response = GraphQLResponse(
            data = buildJsonObject { put("x", JsonPrimitive(1)) },
            errors = listOf(GraphQLError("boom"), GraphQLError("again")),
        )
        val encoded = GraphQLJson.encodeToString(GraphQLResponse.serializer(), response)
        assertEquals(response, GraphQLJson.decodeFromString(GraphQLResponse.serializer(), encoded))
    }

    @Test
    fun `the envelope decodes with only data, only errors, or neither`() {
        val empty = GraphQLJson.decodeFromString(GraphQLResponse.serializer(), "{}")
        assertNull(empty.data)
        assertNull(empty.errors)

        val dataOnly = GraphQLJson.decodeFromString(GraphQLResponse.serializer(), """{"data":{"x":1}}""")
        assertNull(dataOnly.errors)
        assertEquals(buildJsonObject { put("x", JsonPrimitive(1)) }, dataOnly.data)

        val errorsOnly = GraphQLJson.decodeFromString(GraphQLResponse.serializer(), """{"errors":[{"message":"e"}]}""")
        assertNull(errorsOnly.data)
        assertEquals("e", errorsOnly.errors!!.single().message)
    }

    @Test
    fun `the envelope ignores unknown keys`() {
        val response = GraphQLJson.decodeFromString(GraphQLResponse.serializer(), """{"data":{"x":1},"errors":null,"extensions":{"trace":1}}""")
        assertEquals(buildJsonObject { put("x", JsonPrimitive(1)) }, response.data)
        assertNull(response.errors)
    }

    @Test
    fun `encoding omits null fields (explicitNulls is off) and emits present ones`() {
        // explicitNulls = false: a null-valued field is omitted (GraphQL "absent" semantics) rather than written
        // as `null`, so an empty envelope is `{}` — not `{"data":null,"errors":null}`. A present field is emitted.
        assertEquals("{}", GraphQLJson.encodeToString(GraphQLResponse.serializer(), GraphQLResponse()))
        assertEquals(
            """{"data":{"x":1}}""",
            GraphQLJson.encodeToString(
                GraphQLResponse.serializer(),
                GraphQLResponse(data = buildJsonObject { put("x", JsonPrimitive(1)) }),
            ),
        )
    }

    @Test
    fun `a non-default encoder writes only the present field`() {
        // A Json with encodeDefaults = false (the kotlinx default) exercises the generated serializer's
        // `shouldEncodeElementDefault(i) || self.field != null` arms in both directions.
        val strict = Json
        assertEquals("""{"data":{"x":1}}""", strict.encodeToString(GraphQLResponse.serializer(), GraphQLResponse(data = buildJsonObject { put("x", JsonPrimitive(1)) })))
        assertEquals("""{"errors":[{"message":"e"}]}""", strict.encodeToString(GraphQLResponse.serializer(), GraphQLResponse(errors = listOf(GraphQLError("e")))))
        assertEquals("{}", strict.encodeToString(GraphQLResponse.serializer(), GraphQLResponse()))
    }

    @Test
    fun `a strict decoder rejects an unknown field`() {
        // Json (no ignoreUnknownKeys) drives the generated deserializer's unknown-element-index throw arm.
        val ex = assertFailsWith<Exception> { Json.decodeFromString(GraphQLResponse.serializer(), """{"bogus":1}""") }
        assertTrue(ex.message != null)
    }

    @Test
    fun `a single error round-trips`() {
        val error = GraphQLError("nope")
        val encoded = GraphQLJson.encodeToString(GraphQLError.serializer(), error)
        assertEquals(error, GraphQLJson.decodeFromString(GraphQLError.serializer(), encoded))
        assertEquals("nope", GraphQLJson.decodeFromString(GraphQLError.serializer(), """{"message":"nope"}""").message)
    }

    @Test
    fun `decoding an error without its required message field fails`() {
        // GraphQLError.message has no default → the generated deserializer's missing-required-field arm.
        assertFailsWith<Exception> { Json.decodeFromString(GraphQLError.serializer(), "{}") }
    }

    @Test
    fun `the error codec handles a strict encoder and an unknown field`() {
        assertEquals("""{"message":"x"}""", Json.encodeToString(GraphQLError.serializer(), GraphQLError("x")))
        assertFailsWith<Exception> { Json.decodeFromString(GraphQLError.serializer(), """{"message":"x","extra":1}""") }
    }
}
