package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class StorageActionsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    @Test fun `adding and removing associations retains all unsaved native form fields`() = runTest {
        val model = SystemStorageModel(admin = true)
        model.addModel("Draft", "Description", "VECTOR", "{\"dimension\":42}", "[]")
        assertEquals("Draft", model.draft.name)
        assertEquals("VECTOR", model.draft.type)
        val key = model.draft.models.single().key
        val values = """[{"key":"$key","modelId":"model-id","configuration":"{invalid"}]"""
        model.addModel("Changed", "Unsaved", "SEARCH", "{invalid", values)
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals(2, model.draft.models.size)
        assertEquals("{invalid", model.draft.models.first().configuration)
        model.removeModel(key, "Changed", "Unsaved", "SEARCH", "{invalid", model.draft.modelsJson)
        assertEquals(1, model.draft.models.size)
        assertEquals("{invalid", model.draft.configuration)
    }
    @Test fun `invalid model configuration retains draft and never calls API`() = runTest {
        val model = SystemStorageModel(admin = true)
        val values = """[{"key":"row","modelId":"00000000-0000-0000-0000-000000000001","configuration":"{invalid"}]"""
        model.save("Storage", "Description", "SEARCH", "{}", values, context { _, _ -> error("Must not execute") })
        assertTrue(model.feedback.failed)
        assertEquals("{invalid", model.draft.models.single().configuration)
        assertEquals("Storage", model.draft.name)
    }
    @Test fun `reindex rejects empty content selection and requires confirmation`() = runTest {
        val model = SystemStorageModel(admin = true)
        val ctx = context { _, _ -> error("Must not execute") }
        model.reindex("", false, false, false, false, true, ctx)
        assertTrue(model.feedback.failed)
        model.reindex("", true, true, true, true, false, ctx)
        assertTrue(model.feedback.failed)
    }
    @Test fun `reindex sends explicit filters for the chosen system`() = runTest {
        val model = SystemStorageModel(admin = true)
        var values: JsonObject? = null
        model.reindex("Chosen", true, false, true, false, true, context { operation, variables ->
            assertEquals("SystemReindex", operation); values = variables
            Json.parseToJsonElement("""{"storageSystems":{"reindex":true}}""")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals(Json.parseToJsonElement("""{"storageName":"Chosen","deleteFirst":true,"metadata":false,"collections":true,"profiles":false}""").jsonObject, assertNotNull(values))
    }
    @Test fun `failed reindex retains content scopes and clean rebuild selection`() = runTest {
        val model = SystemStorageModel(admin = true)
        model.reindex("Chosen", true, false, true, false, true, context { _, _ -> error("Queue unavailable") })
        assertTrue(model.feedback.failed)
        assertEquals(SystemReindexDraft(true, false, true, false, true), model.reindexDraft)
    }
    @Test fun `search edit preserves permissions and visibility from the authoritative configuration`() = runTest {
        val model = SystemSearchModel()
        var saved: JsonObject? = null
        model.save("{\"expressions\":{\"metadata\":\"metadata\"}}", context { operation, variables ->
            when (operation) {
                "SystemSearchConfiguration" -> Json.parseToJsonElement("""{"configurations":{"configuration":{"id":"configuration-id","key":"search","description":"Custom description","value":{},"public":true}}}""")
                "SystemSetConfiguration" -> { saved = variables.getValue("input").jsonObject; Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"configuration-id"}}}""") }
                else -> error(operation)
            }
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        val input = assertNotNull(saved)
        assertTrue(input.getValue("public").jsonPrimitive.boolean)
        assertEquals("Custom description", input.getValue("description").jsonPrimitive.content)
        assertTrue(input["permissions"] == null || input["permissions"] == JsonNull)
    }
}
