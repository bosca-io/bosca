package bosca.bml.graphql

import bosca.bml.graphql.generated.GetNote
import bosca.bml.graphql.generated.GetNoteData
import bosca.bml.graphql.generated.Visibility
import bosca.graphql.client.GraphQLJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * server-side end-to-end (native-safe, no Apollo): a BML `<script server>` uses a *generated typed
 * operation* — the Bosca-native codegen output, wired here via composite substitution of `io.bosca:bosca-graphql-client`
 * (no publish) — over core-bml's own [GraphQLClient] data plane. The generated op supplies the document, encodes
 * typed variables, and decodes the response into typed Kotlin with EXPLICIT serializers (GraalVM-native-safe),
 * exactly as [GraphQLClient]'s KDoc envisions ("Typed operation wrappers are generated on top of this").
 */
class GeneratedOperationServerTest {

    /** A stub of core-bml's server-side data plane returning a canned GraphQL `data` payload. */
    private class StubClient(private val data: JsonElement) : GraphQLClient {
        var lastQuery: String? = null
        var lastVariables: JsonObject? = null
        var lastOperationName: String? = null

        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
            lastQuery = query
            lastVariables = variables
            lastOperationName = operationName
            return data
        }
    }

    @Test
    fun `a script-server call runs a generated typed operation over the BML GraphQL client`() = runBlocking {
        val response = GraphQLJson.parseToJsonElement("""{"note":{"id":"9","visibility":"PRIVATE"}}""")
        val client = StubClient(response)

        // Exactly what a <script server> body does with a generated op:
        val raw = client.execute(GetNote.document, GetNote.encodeVariables(GetNote.Variables(id = "9")), GetNote.operationName)
        val data: GetNoteData = GetNote.decodeData(raw)

        // Typed end-to-end — no unknown, the enum decodes to the typed constant.
        assertEquals("9", data.note?.id)
        assertEquals(Visibility.PRIVATE, data.note?.visibility)

        // The generated op drove the transport with the right document, name, and encoded variables.
        assertEquals("GetNote", client.lastOperationName)
        assertEquals("query GetNote(\$id: ID!) { note(id: \$id) { id visibility } }", client.lastQuery)
        assertEquals("9", (client.lastVariables!!.getValue("id") as JsonPrimitive).content)
    }
}
