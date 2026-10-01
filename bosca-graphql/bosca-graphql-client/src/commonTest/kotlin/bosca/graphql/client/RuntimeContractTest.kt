package bosca.graphql.client

import bosca.graphql.client.generated.Find
import bosca.graphql.client.generated.FindData
import bosca.graphql.client.generated.GetAccount
import bosca.graphql.client.generated.GetNode
import bosca.graphql.client.generated.GetNodeData
import bosca.graphql.client.generated.GetNote
import bosca.graphql.client.generated.GetSpec
import bosca.graphql.client.generated.GetSpecByKey
import bosca.graphql.client.generated.GetUser
import bosca.graphql.client.generated.ISpecFields
import bosca.graphql.client.generated.ListNotes
import bosca.graphql.client.generated.NoteFilter
import bosca.graphql.client.generated.Search
import bosca.graphql.client.generated.SearchFilter
import bosca.graphql.client.generated.Status
import bosca.graphql.client.generated.Visibility
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the runtime contract end-to-end against the generated [GetUser] fixture: the `execute` bridge
 * encodes typed variables, sends the document, and decodes the response's `data` into typed Kotlin —
 * round-tripping through a stub [GraphQLClient] (no real transport, no Apollo).
 */
class RuntimeContractTest {

    private class StubClient(private val response: GraphQLResponse) : GraphQLClient {
        var lastDocument: String? = null
            private set
        var lastVariables: JsonObject? = null
            private set
        var lastOperationName: String? = null
            private set

        override suspend fun execute(document: String, variables: JsonObject?, operationName: String?): GraphQLResponse {
            lastDocument = document
            lastVariables = variables
            lastOperationName = operationName
            return response
        }
    }

    @Test
    fun `encodes variables, sends the document, and decodes typed data`() = runTest {
        val data = GraphQLJson.parseToJsonElement("""{"user":{"id":"1","name":"Ada","email":null}}""")
        val client = StubClient(GraphQLResponse(data = data))

        val result = client.execute(GetUser, GetUser.Variables(id = "1"))

        assertEquals("1", result.user?.id)
        assertEquals("Ada", result.user?.name)
        assertNull(result.user?.email)
        // the document, operation name, and encoded variables reached the transport
        assertEquals("GetUser", client.lastOperationName)
        assertTrue(client.lastDocument!!.startsWith("query GetUser("), client.lastDocument!!)
        assertEquals("1", (client.lastVariables!!["id"] as JsonPrimitive).content)
    }

    @Test
    fun `decode ignores unknown response fields like __typename`() = runTest {
        val data = GraphQLJson.parseToJsonElement("""{"user":{"id":"1","name":"Ada","email":"a@x","__typename":"User"}}""")
        val result = StubClient(GraphQLResponse(data = data)).execute(GetUser, GetUser.Variables("1"))
        assertEquals("a@x", result.user?.email)
    }

    @Test
    fun `a null object field decodes to null`() = runTest {
        val data = GraphQLJson.parseToJsonElement("""{"user":null}""")
        val result = StubClient(GraphQLResponse(data = data)).execute(GetUser, GetUser.Variables("x"))
        assertNull(result.user)
    }

    @Test
    fun `an errors payload raises GraphQLClientException`() = runTest {
        val client = StubClient(GraphQLResponse(errors = listOf(GraphQLError("boom"))))
        val ex = assertFailsWith<GraphQLClientException> { client.execute(GetUser, GetUser.Variables("1")) }
        assertTrue(ex.message!!.contains("boom"), ex.message)
    }

    @Test
    fun `a response with no data raises`() = runTest {
        val client = StubClient(GraphQLResponse(data = null, errors = null))
        assertFailsWith<GraphQLClientException> { client.execute(GetUser, GetUser.Variables("1")) }
    }

    @Test
    fun `an empty errors array is not treated as a failure`() = runTest {
        // errors present but empty → takeIf { isNotEmpty() } is false → no throw; data still decodes.
        val data = GraphQLJson.parseToJsonElement("""{"user":{"id":"1","name":"Ada","email":null}}""")
        val result = StubClient(GraphQLResponse(data = data, errors = emptyList())).execute(GetUser, GetUser.Variables("1"))
        assertEquals("1", result.user?.id)
    }

    @Test
    fun `enums, input objects, lists, and custom scalars round-trip`() = runTest {
        val data = GraphQLJson.parseToJsonElement(
            """{"search":{"id":"1","status":"ACTIVE","tags":["a","b"],"score":42}}""",
        )
        val client = StubClient(GraphQLResponse(data = data))

        val result = client.execute(Search, Search.Variables(SearchFilter(term = "ada", status = Status.ACTIVE)))

        assertEquals(Status.ACTIVE, result.search?.status)
        assertEquals(listOf("a", "b"), result.search?.tags)
        assertEquals(42L, result.search?.score)
        // the typed input object (enum + nullable defaults) was encoded into variables
        val filter = client.lastVariables!!["filter"]!!.jsonObject
        assertEquals("ada", filter["term"]!!.jsonPrimitive.content)
        assertEquals("ACTIVE", filter["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `aliases and a flattened named fragment round-trip`() = runTest {
        // response is flat: the fragment's fields (id, name) merge into the object alongside the alias (contact)
        val data = GraphQLJson.parseToJsonElement("""{"account":{"id":"1","name":"Ada","contact":"a@x"}}""")
        val client = StubClient(GraphQLResponse(data = data))

        val result = client.execute(GetAccount, GetAccount.Variables(id = "1"))

        assertEquals("1", result.account?.id)       // from fragment UserFields
        assertEquals("Ada", result.account?.name)   // from fragment UserFields
        assertEquals("a@x", result.account?.contact) // alias of `email`
        // the document carries the fragment definition for the server
        assertTrue("fragment UserFields on User" in client.lastDocument!!, client.lastDocument!!)
    }

    @Test
    fun `an interface selection decodes into the right sealed subtype by __typename`() {
        val data = GraphQLJson.parseToJsonElement("""{"node":{"__typename":"User","id":"1","name":"Ada"}}""")
        val node = assertIs<GetNodeData.Node.User>(GetNode.decodeData(data).node)
        assertEquals("1", node.id)     // common interface field (hoisted as override val)
        assertEquals("Ada", node.name) // narrowed field
    }

    @Test
    fun `an unknown interface __typename decodes into the forward-compatible Other branch`() {
        val data = GraphQLJson.parseToJsonElement("""{"node":{"__typename":"Robot","id":"9"}}""")
        val node = assertIs<GetNodeData.Node.Other>(GetNode.decodeData(data).node)
        assertEquals("9", node.id)
    }

    @Test
    fun `a union selection decodes into the right sealed subtype`() {
        val data = GraphQLJson.parseToJsonElement("""{"result":{"__typename":"Post","id":"2","title":"Hi"}}""")
        val result = assertIs<FindData.Result.Post>(Find.decodeData(data).result)
        assertEquals("2", result.id)
        assertEquals("Hi", result.title)
    }

    @Test
    fun `an unknown union member decodes into the Other object`() {
        val data = GraphQLJson.parseToJsonElement("""{"result":{"__typename":"Ghost"}}""")
        assertTrue(Find.decodeData(data).result is FindData.Result.Other)
    }

    @Test
    fun `two operations spreading one fragment unify under its interface with nested access`() {
        // Both operations spread `...SpecFields`; the generator emits ISpecFields (with nested ISpecFieldsStatus /
        // ISpecFieldsProject). Each operation's distinct concrete type implements it via covariant overrides, so a
        // helper typed on ISpecFields accepts both AND reads the nested object fields through the interface.
        fun describe(s: ISpecFields) = "${s.key}:${s.status.name}/${s.project?.key}"

        val byId = GetSpec.decodeData(
            GraphQLJson.parseToJsonElement("""{"spec":{"id":"1","key":"S-1","status":{"name":"open","category":"x"},"project":{"key":"P","name":"Proj"}}}"""),
        )
        val byKey = GetSpecByKey.decodeData(
            GraphQLJson.parseToJsonElement("""{"specByKey":{"id":"2","key":"S-2","status":{"name":"done","category":"y"},"project":null}}"""),
        )

        assertEquals("S-1:open/P", byId.spec?.let { describe(it) })           // GetSpec's concrete type as ISpecFields
        assertEquals("S-2:done/null", byKey.specByKey?.let { describe(it) })  // GetSpecByKey's, unified
        assertIs<ISpecFields>(byId.spec)
        assertIs<ISpecFields>(byKey.specByKey)
        assertEquals("x", byId.spec.status.category)                        // nested object field decodes
    }

    @Test
    fun `hoisted multi-operation output round-trips with shared types across operations`() {
        // ListNotes: a list selection + the shared Visibility enum + a NoteFilter input variable.
        val listData = GraphQLJson.parseToJsonElement(
            """{"notes":[{"id":"1","title":"A","visibility":"PUBLIC"},{"id":"2","title":"B","visibility":null}]}""",
        )
        val notes = ListNotes.decodeData(listData).notes
        assertEquals(2, notes.size)
        assertEquals(Visibility.PUBLIC, notes[0].visibility)
        assertNull(notes[1].visibility)

        // The hoisted input object + enum encode into variables.
        val vars = ListNotes.encodeVariables(ListNotes.Variables(NoteFilter(term = "x", visibility = Visibility.PRIVATE)))
        assertEquals("PRIVATE", vars["filter"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)

        // The SAME shared Visibility enum decodes in a different operation (proves cross-file reuse compiles + works).
        val noteData = GraphQLJson.parseToJsonElement("""{"note":{"id":"9","visibility":"PRIVATE"}}""")
        assertEquals(Visibility.PRIVATE, GetNote.decodeData(noteData).note?.visibility)
    }
}
