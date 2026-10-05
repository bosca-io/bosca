package bosca.ai.kit.agents

import ai.koog.serialization.JSONArray
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class JSONElementSerializationTest {

    private val serializer = KitSerializer(KotlinxSerializer(koogJson(Json { ignoreUnknownKeys = true; isLenient = true })))

    @Test
    fun `JSONObject encodes to a string without reflective serializer lookup`() {
        // This is exactly what ToolBase.encodeResultToString does with a tool result; it must
        // route through the explicit JSONElementSerializer, not typeToken<JSONElement>().
        val element = JSONObject(
            mapOf(
                "status" to JSONPrimitive.of("ok"),
                "count" to JSONPrimitive.of(2),
                "items" to JSONArray(listOf(JSONPrimitive.of("a"), JSONPrimitive.of("b"))),
            )
        )

        val encoded = serializer.encodeJSONElementToString(element)

        assertEquals("""{"status":"ok","count":2,"items":["a","b"]}""", encoded)
    }

    @Test
    fun `reflective tokens for JSON element types are rewritten to explicit serializers`() {
        // Koog's McpTool passes typeToken<JSONObject>() as argsType; the wrapper must rewrite it
        // so the delegate never resolves the @Serializable(with = ...) annotation reflectively.
        val element = JSONObject(mapOf("key" to JSONPrimitive.of("value")))

        val encoded = serializer.encodeToString(element, typeToken<JSONObject>())
        val decoded: JSONObject = serializer.decodeFromString(encoded, typeToken<JSONObject>())

        assertEquals(element, decoded)
        assertEquals(element, serializer.decodeFromJSONElement(serializer.encodeToJSONElement(element, typeToken<JSONElement>()), typeToken<JSONElement>()))
    }

    @Test
    fun `JSONElement round-trips through string encoding`() {
        val element = JSONObject(mapOf("nested" to JSONObject(mapOf("value" to JSONPrimitive.of(true)))))

        val decoded = serializer.decodeJSONElementFromString(serializer.encodeJSONElementToString(element))

        assertEquals(element, decoded)
    }
}
