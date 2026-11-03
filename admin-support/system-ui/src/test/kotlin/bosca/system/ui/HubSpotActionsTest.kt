package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class HubSpotActionsTest {
    private fun context(handler: suspend (String, JsonObject?) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables)
    })
    private suspend fun save(model: SystemHubSpotModel, draft: SystemHubSpotDraft, ctx: RenderContext) = model.save(draft.token, draft.clearToken, draft.associationTypeId, draft.associationCategory, Json.encodeToString(draft.listIds), Json.encodeToString(draft.subscriptionIds), draft.genericExpression, draft.organizationExpression, ctx)

    @Test fun `settings retain fresh secrets ACLs extra fields and both expression mappings`() = runTest {
        var stored: JsonElement = Json.parseToJsonElement("""{"token":"stored-secret","custom":true,"expressions":{"other":"keep"}}""")
        val ctx = context { operation, variables ->
            if (operation == "SystemSetConfiguration") {
                val input = requireNotNull(variables).getValue("input").jsonObject
                assertTrue(input["permissions"] == null || input["permissions"] is JsonNull)
                assertFalse(input.getValue("public").jsonPrimitive.boolean)
                stored = input.getValue("value")
                Json.parseToJsonElement("""{"configurations":{"setConfiguration":{"id":"config"}}}""")
            } else buildJsonObject { put("configurations", buildJsonObject { put("configuration", buildJsonObject {
                put("id", "config"); put("description", "Existing"); put("public", false); put("value", stored)
            }) }) }
        }
        val model = SystemHubSpotModel(); model.reload(ctx)
        assertTrue(model.hasToken); assertFalse(Json.encodeToString(model).contains("stored-secret"))
        stored = JsonObject(stored.jsonObject + ("token" to JsonPrimitive("newer-secret")))
        val draft = SystemHubSpotDraft(associationTypeId = "123", associationCategory = "USER_DEFINED", listIds = listOf("1", "2"), subscriptionIds = listOf("3"), genericExpression = "profile.name", organizationExpression = "organization.name")
        save(model, draft, ctx)
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("newer-secret", stored.jsonObject.getValue("token").jsonPrimitive.content)
        assertTrue(stored.jsonObject.getValue("custom").jsonPrimitive.boolean)
        assertEquals("keep", stored.jsonObject.getValue("expressions").jsonObject.getValue("other").jsonPrimitive.content)
        assertEquals(draft, model.draft)
        save(model, draft.copy(clearToken = true), ctx); assertFalse(model.hasToken)
        save(model, draft.copy(token = "replacement"), ctx)
        assertEquals("replacement", stored.jsonObject.getValue("token").jsonPrimitive.content)
        assertEquals("", model.draft.token)
    }

    @Test fun `invalid and denied saves retain complete drafts`() = runTest {
        val model = SystemHubSpotModel(loaded = true)
        val draft = SystemHubSpotDraft(token = "typed", associationTypeId = "bad", genericExpression = "unfinished(", listIds = listOf("list"))
        save(model, draft, context { _, _ -> error("Must not query") })
        assertTrue(model.feedback.failed); assertEquals(draft, model.draft)
        save(model, draft.copy(associationTypeId = "279"), context { _, _ -> error("Denied configuration read") })
        assertEquals("Denied configuration read", model.feedback.message); assertEquals("typed", model.draft.token)
    }

    @Test fun `search minimum and profile context use typed authorized server operations`() = runTest {
        val model = SystemHubSpotPreview(admin = true)
        val ctx = context { operation, variables ->
            when (operation) {
                "SystemHubSpotProfiles" -> {
                    assertEquals("Ada", variables?.get("query")?.jsonPrimitive?.content)
                    Json.parseToJsonElement("""{"search":{"search":{"documents":[{"profile":null},{"profile":{"id":"profile","name":"Ada","type":"GENERIC"}}]}}}""")
                }
                else -> Json.parseToJsonElement("""{"profiles":{"profile":{"id":"profile","name":"Ada","type":"GENERIC","thirdparty":{"context":{"profile":{"name":"Ada"}}}}}}""")
            }
        }
        model.search("a", context { _, _ -> error("Short search") }); assertTrue(model.profiles.isEmpty())
        model.search("Ada", ctx); assertEquals(1, model.profiles.size)
        model.select("profile", ctx); assertEquals("GENERIC", model.profileType); assertEquals("Ada", model.profileName)
        assertEquals("Ada", model.context?.jsonObject?.get("profile")?.jsonObject?.get("name")?.jsonPrimitive?.content)
        model.select("", ctx); assertNull(model.context)
    }

    @Test fun `sync failures and non administrator actions remain visible`() = runTest {
        val denied = SystemHubSpotPreview()
        val unused = context { _, _ -> error("Must not query") }
        denied.search("Ada", unused); assertTrue(denied.feedback.failed)
        denied.select("profile", unused); assertTrue(denied.feedback.failed)
        denied.sync(true, unused); assertTrue(denied.feedback.failed)
        val model = SystemHubSpotPreview(admin = true, profileId = "profile", profileName = "Ada")
        model.sync(false, context { _, _ -> error("Provider rejected sync") }); assertEquals("Provider rejected sync", model.feedback.message)
        model.sync(true, context { _, _ -> Json.parseToJsonElement("""{"profiles":{"profile":{"thirdparty":{"syncAll":false}}}}""") })
        assertTrue(model.feedback.failed)
    }
}
