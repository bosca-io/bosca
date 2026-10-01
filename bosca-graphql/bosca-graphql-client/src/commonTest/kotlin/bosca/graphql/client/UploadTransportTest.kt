package bosca.graphql.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives the multipart upload pieces: the [Upload] scalar serializer, [buildMultipartForm], and the upload bridge. */
class UploadTransportTest {

    @Serializable
    private data class Vars(
        @Serializable(with = UploadSerializer::class) val file: Upload,
        val name: String,
    )

    private object UploadOp : BoscaOperation<Vars, JsonObject> {
        override val operationName: String = "DoUpload"
        override val document: String = "mutation DoUpload(\$file: Upload!, \$name: String!) { upload(file: \$file, name: \$name) }"
        override fun encodeVariables(variables: Vars): JsonObject =
            GraphQLJson.encodeToJsonElement(Vars.serializer(), variables).jsonObject
        override fun decodeData(data: JsonElement): JsonObject = data.jsonObject
    }

    private fun upload(name: String = "a.txt") = Upload(name, "text/plain", "hi".encodeToByteArray())

    @Test
    fun `the Upload scalar encodes to JSON null inside typed variables`() {
        val json = UploadOp.encodeVariables(Vars(upload(), "n"))
        assertEquals(JsonNull, json["file"])               // file position nulled — bytes ride as a form part
        assertEquals("n", json.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `decoding an Upload is unsupported (input-only scalar)`() {
        assertFailsWith<IllegalStateException> {
            GraphQLJson.decodeFromJsonElement(UploadSerializer, JsonNull)
        }
    }

    @Test
    fun `buildMultipartForm emits the operations envelope, the map, and the files in order`() {
        val f0 = upload("a.txt")
        val f1 = upload("b.txt")
        val form = buildMultipartForm(
            document = "mutation M { m }",
            variables = buildJsonObject { put("file", JsonNull); put("other", JsonNull) },
            operationName = "M",
            uploads = listOf(GraphQLUpload("variables.file", f0), GraphQLUpload("variables.other", f1)),
        )
        val operations = GraphQLJson.parseToJsonElement(form.operations).jsonObject
        assertEquals("mutation M { m }", operations.getValue("query").jsonPrimitive.content)
        assertEquals("M", operations.getValue("operationName").jsonPrimitive.content)
        assertTrue("file" in operations.getValue("variables").jsonObject)
        assertEquals("""{"0":["variables.file"],"1":["variables.other"]}""", form.map)
        assertEquals(listOf(f0, f1), form.files)
    }

    @Test
    fun `buildMultipartForm omits operationName and variables when absent`() {
        val form = buildMultipartForm("{ m }", null, null, emptyList())
        val operations = GraphQLJson.parseToJsonElement(form.operations).jsonObject
        assertTrue("operationName" !in operations && "variables" !in operations)
        assertEquals("{}", form.map)
        assertTrue(form.files.isEmpty())
    }

    /** Records the call and returns a fixed response — the base [execute] is never used by the upload bridge. */
    private class StubUploadClient(private val response: GraphQLResponse) : GraphQLUploadClient {
        var lastDocument: String? = null
        var lastUploads: List<GraphQLUpload>? = null
        override suspend fun execute(document: String, variables: JsonObject?, operationName: String?): GraphQLResponse =
            error("base execute not used by the upload path")
        override suspend fun executeUpload(document: String, variables: JsonObject?, operationName: String?, uploads: List<GraphQLUpload>): GraphQLResponse {
            lastDocument = document
            lastUploads = uploads
            return response
        }
    }

    @Test
    fun `the upload bridge encodes variables, forwards uploads, and decodes typed data`() = runTest {
        val u = upload()
        val client = StubUploadClient(GraphQLResponse(data = buildJsonObject { put("ok", JsonPrimitive(true)) }))
        val data = client.executeUpload(UploadOp, Vars(u, "n"), listOf(GraphQLUpload("variables.file", u)))
        assertTrue(data.getValue("ok").jsonPrimitive.boolean)
        assertTrue(client.lastDocument!!.startsWith("mutation DoUpload("))
        assertEquals(listOf(GraphQLUpload("variables.file", u)), client.lastUploads)
    }

    @Test
    fun `the upload bridge raises on a response error envelope`() = runTest {
        val u = upload()
        val client = StubUploadClient(GraphQLResponse(errors = listOf(GraphQLError("boom"))))
        val ex = assertFailsWith<GraphQLClientException> {
            client.executeUpload(UploadOp, Vars(u, "n"), listOf(GraphQLUpload("variables.file", u)))
        }
        assertTrue(ex.message!!.contains("boom"))
    }

    @Test
    fun `the upload bridge raises when the response has no data`() = runTest {
        val u = upload()
        val client = StubUploadClient(GraphQLResponse(data = null, errors = null))
        assertFailsWith<GraphQLClientException> {
            client.executeUpload(UploadOp, Vars(u, "n"), listOf(GraphQLUpload("variables.file", u)))
        }
    }

    @Test
    fun `the upload bridge treats an empty errors array as success`() = runTest {
        val u = upload()
        val client = StubUploadClient(GraphQLResponse(data = buildJsonObject { put("ok", JsonPrimitive(true)) }, errors = emptyList()))
        val data = client.executeUpload(UploadOp, Vars(u, "n"), listOf(GraphQLUpload("variables.file", u)))
        assertTrue(data.getValue("ok").jsonPrimitive.boolean)
    }
}
