package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationActionsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    @Test fun `configuration list neither requests nor serializes values`() = runTest {
        val model = SystemConfigurationsModel(admin = true)
        val ctx = RenderContext(gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                assertEquals("SystemConfigurations", operationName)
                assertFalse(Regex("\\bvalue\\b").containsMatchIn(query), query)
                return Json.parseToJsonElement("""{"configurations":{"all":[{"id":"config-id","key":"app.test","description":"Private setting","public":false,"value":"list-secret-sentinel"}]}}""")
            }
        })
        model.reload(ctx)
        assertEquals("app.test", model.entries.single().key)
        assertFalse(Json.encodeToString(SystemConfigurationsModel.serializer(), model).contains("list-secret-sentinel"))
    }
    @Test fun `editing loads a value on demand and dismissal clears the draft`() = runTest {
        val model = SystemConfigurationsModel(admin = true)
        model.edit("app.test", context { operation, variables ->
            assertEquals("SystemConfiguration", operation)
            assertEquals("app.test", variables.getValue("key").jsonPrimitive.content)
            Json.parseToJsonElement("""{"configurations":{"configuration":{"id":"config-id","key":"app.test","description":"Private setting","public":false,"value":{"secret":"editor-secret-sentinel"}}}}""")
        })
        assertTrue(model.editing)
        assertTrue(model.draft.value.contains("editor-secret-sentinel"))
        assertEquals(1, model.editorRequest)
        model.feedback.failed = true
        model.feedback.message = "Invalid draft"
        model.cancel()
        assertFalse(model.editing)
        assertNull(model.editingKey)
        assertEquals(SystemConfigurationDraft(), model.draft)
        assertFalse(model.feedback.failed)
        assertEquals("", model.feedback.message)
        assertFalse(Json.encodeToString(SystemConfigurationsModel.serializer(), model).contains("editor-secret-sentinel"))
        model.newEntry()
        assertTrue(model.editing)
        assertEquals(2, model.editorRequest)
        assertEquals(SystemConfigurationDraft(), model.draft)
    }
    @Test fun `edit updates JSON without requiring MANAGE or sending an ACL`() = runTest {
        val model = SystemConfigurationsModel(admin = false, editing = true, editingKey = "app.test")
        var saved: JsonObject? = null
        model.save("app.test", "Updated", "[1,true,null]", true, context { operation, variables ->
            when (operation) {
                "SystemConfiguration" -> Json.parseToJsonElement("""{"configurations":{"configuration":{"id":"config-id","key":"app.test","description":"Old","public":false,"value":{}}}}""")
                "SystemSetConfiguration" -> {
                    saved = variables.getValue("input").jsonObject
                    Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config-id"}}}""")
                }
                "SystemConfigurations" -> Json.parseToJsonElement("""{"configurations":{"all":[]}}""")
                else -> error(operation)
            }
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        val input = assertNotNull(saved)
        assertEquals(Json.parseToJsonElement("[1,true,null]"), input["value"])
        assertTrue(input["permissions"] == null || input["permissions"] == JsonNull)
        assertFalse(model.editing)
        assertEquals(SystemConfigurationDraft(), model.draft)
    }
    @Test fun `invalid JSON keeps the form and never changes configuration`() = runTest {
        val model = SystemConfigurationsModel(admin = true)
        model.save("app.test", "Unsaved", "{invalid", true, context { _, _ -> error("Must not execute") })
        assertTrue(model.feedback.failed)
        assertTrue(model.editing)
        assertEquals(SystemConfigurationDraft("app.test", "Unsaved", "{invalid", true), model.draft)
    }
    @Test fun `new entry cannot overwrite an existing configuration`() = runTest {
        val model = SystemConfigurationsModel(admin = true)
        model.save("app.test", "", "{}", false, context { operation, _ ->
            assertEquals("SystemConfiguration", operation)
            Json.parseToJsonElement("""{"configurations":{"configuration":{"id":"config-id","key":"app.test","description":"Old","public":false,"value":{}}}}""")
        })
        assertTrue(model.feedback.failed)
        assertTrue("already exists" in model.feedback.message)
    }
    @Test fun `unconfirmed destructive operations never call the API`() = runTest {
        val ctx = context { _, _ -> error("Must not execute") }
        val config = SystemConfigurationsModel(admin = true)
        config.delete("app.test", false, ctx)
        assertTrue(config.feedback.failed)
        val utilities = SystemUtilitiesModel(admin = true)
        for (operation in listOf("expire-all-jobs", "clear-job-locks")) {
            utilities.execute(operation, false, ctx)
            assertTrue(utilities.feedback.failed)
        }
    }
    @Test fun `utility false and transport errors remain visible`() = runTest {
        val model = SystemUtilitiesModel(admin = true)
        model.execute("clear-cdn-cache", false, context { _, _ -> Json.parseToJsonElement("""{"clearCdnCache":false}""") })
        assertTrue(model.feedback.failed)
        model.execute("clear-cache", false, context { _, _ -> error("Cache unavailable") })
        assertTrue(model.feedback.failed)
        assertEquals("Cache unavailable", model.feedback.message)
    }
}
