package bosca.graphql.codegen.cli

import bosca.graphql.client.GraphQLJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The schema-refresh path: request assembly + introspection → SDL, with the HTTP transport stubbed. */
class SchemaDownloaderTest {

    @Test
    fun `requestBody wraps and escapes the query as valid JSON`() {
        val body = SchemaDownloader.requestBody("query Q { a(s: \"x\") }")
        val parsed = GraphQLJson.parseToJsonElement(body)
        assertEquals("query Q { a(s: \"x\") }", parsed.jsonObject.getValue("query").jsonPrimitive.content)
    }

    @Test
    fun `download posts the introspection query and converts the response to SDL`() {
        val introspection = """
            {"data":{"__schema":{"queryType":{"name":"Query"},"mutationType":null,"subscriptionType":null,"types":[
              {"kind":"OBJECT","name":"Query","interfaces":[],"fields":[
                {"name":"ping","args":[],"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"String","ofType":null}}}
              ]}
            ]}}}
        """.trimIndent()

        var capturedEndpoint: String? = null
        var capturedBody: String? = null
        var capturedHeaders: Map<String, String>? = null
        val sdl = SchemaDownloader.download(
            endpoint = "https://example/graphql",
            headers = mapOf("Authorization" to "Bearer t"),
        ) { endpoint, body, headers ->
            capturedEndpoint = endpoint
            capturedBody = body
            capturedHeaders = headers
            introspection
        }

        assertTrue("type Query {" in sdl && "ping: String!" in sdl, sdl)
        assertEquals("https://example/graphql", capturedEndpoint)
        assertTrue(capturedBody!!.contains("IntrospectionQuery"), capturedBody)
        assertEquals("Bearer t", capturedHeaders!!["Authorization"])
    }

    @Test
    fun `download defaults to no extra headers`() {
        var capturedHeaders: Map<String, String>? = null
        val sdl = SchemaDownloader.download(endpoint = "https://example/graphql") { _, _, headers ->
            capturedHeaders = headers
            """{"data":{"__schema":{"queryType":{"name":"Query"},"types":[{"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]}]}}}"""
        }
        assertEquals(emptyMap(), capturedHeaders) // the default headers parameter
        assertTrue("type Query {" in sdl, sdl)
    }
}
