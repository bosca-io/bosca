package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import java.time.LocalDateTime
import java.time.ZoneOffset

class SecurityActionsTest {
    @Test fun `rejected persona assignments reopen only their dialog and retain the detail revision`() = runTest {
        val model = SystemPersonasModel(id = "persona", admin = true)
        val rejected = context { _, _ -> error("Profile not found") }
        model.assign("profile", rejected)
        val first = model.assignmentOpenRequest
        assertTrue(first.isNotBlank())
        assertEquals(0L, model.editorRequest)
        model.assign("profile", rejected)
        assertNotEquals(first, model.assignmentOpenRequest)
        model.selectAll("Draft", "Description", true)
        assertEquals("", model.assignmentOpenRequest)
        assertEquals(1L, model.editorRequest)
    }
    @Test fun `new token expiry defaults to ninety days and can still be cleared`() {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val model = SystemTokensModel()
        model.openCreate()
        val expiry = LocalDateTime.parse(model.draft.expires)
        assertTrue(expiry.isAfter(now.plusDays(89)) && expiry.isBefore(now.plusDays(91)))
        model.applyScopes("Token", "", "[]", "__self__", "")
        assertEquals("", model.draft.expires)
    }
    @Test fun `scope dialog applies choices without losing token fields and resets on a new edit`() = runTest {
        val model = SystemTokensModel()
        model.openCreate()
        model.openScopes("Draft token", "Draft description", "[\"content:view\"]", "__self__", "2030-01-01T12:00")
        assertTrue(model.scopeEditorOpen)
        assertTrue(model.scopeOpenRequest.isNotBlank())
        val request = model.editorRequest
        model.applyScopes("Draft token", "Draft description", "[\"content:view\",\"git:read\"]", "__self__", "2030-01-01T12:00")
        assertFalse(model.scopeEditorOpen)
        assertTrue(model.scopeEditorCompleted)
        assertTrue(model.editorRequest > request)
        assertEquals("Draft token", model.draft.name)
        assertEquals("Draft description", model.draft.description)
        assertEquals("2030-01-01T12:00", model.draft.expires)
        assertEquals(listOf("content:view", "git:read"), model.draft.scopes)
        model.openCreate()
        assertFalse(model.scopeEditorOpen)
        assertFalse(model.scopeEditorCompleted)
        assertEquals("", model.scopeOpenRequest)
        assertTrue(model.draft.scopes.isEmpty())
    }
    @Test fun `principal page links retain an encoded search and deleted filter`() {
        val model = SystemPrincipalsModel(admin = true, offset = 25, includeDeleted = true, hasNext = true, query = "reader+test@example.test & other")
        for ((href, offset) in listOf(model.previousHref to "0", model.nextHref to "50")) {
            val parameters = java.net.URI(href).rawQuery.split('&').associate {
                val (key, value) = it.split('=', limit = 2)
                key to java.net.URLDecoder.decode(value, Charsets.UTF_8)
            }
            assertEquals(offset, parameters["offset"])
            assertEquals("true", parameters["includeDeleted"])
            assertEquals(model.query, parameters["q"])
        }
        assertEquals("", SystemPrincipalsModel().previousHref)
        assertEquals("", SystemPrincipalsModel().nextHref)
    }

    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun token(owner: String = "owner") = SystemTokenView(42, owner, "Token", "Description", "bsk_prefix", listOf("content:view"), null, null, null, null, null, true)
    @Test fun `artifact scope builder preserves the rest of the unsaved token draft`() = runTest {
        val model = SystemTokensModel(currentPrincipalId = "owner")
        model.addScope("Draft token", "Unsaved description", "[\"content:view\"]", "__self__", "2030-01-01T12:00", "raw", "builds/tool", "1.*", "[\"pull\",\"push\"]")
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("Draft token", model.draft.name)
        assertEquals("Unsaved description", model.draft.description)
        assertEquals("2030-01-01T12:00", model.draft.expires)
        assertEquals(listOf("content:view", "artifacts:raw:builds/tool:1.*:pull,push"), model.draft.scopes)
        model.addScope("Draft token", "", "[]", "__self__", "", "raw", "builds/tool", "*", "[\"admin\"]")
        assertFalse(model.feedback.failed); assertEquals(listOf("artifacts:raw:builds/tool:*:admin"), model.draft.scopes)
        model.addScope("Draft token", "", "[]", "__self__", "", "raw", "builds/tool", "*", "[\"delete\"]")
        assertTrue(model.feedback.failed)
    }
    @Test fun `saving an empty scope selection explicitly clears restrictions and blank description`() = runTest {
        val model = SystemTokensModel(id = 42, currentPrincipalId = "owner", token = token())
        var sent: JsonObject? = null
        model.save("Renamed", "", "[]", "__self__", "", context { operation, variables ->
            when (operation) {
                "SystemEditToken" -> {
                    sent = variables
                    Json.parseToJsonElement("""{"security":{"apiTokens":{"edit":{"id":42}}}}""")
                }
                "SystemTokenScopes" -> Json.parseToJsonElement("""{"security":{"apiTokens":{"availableScopes":[]}}}""")
                "SystemToken" -> Json.parseToJsonElement("""{"security":{"apiTokens":{"token":{"id":42,"principalId":"owner","name":"Renamed","description":"","tokenPrefix":"prefix","scopes":null,"allowedGroups":null,"expiresAt":null,"lastUsedAt":null,"lastUsedIp":null,"revokedAt":null,"active":true}}}}""")
                else -> error(operation)
            }
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertTrue(assertNotNull(sent).getValue("clearScopes").jsonPrimitive.boolean)
        assertEquals("", assertNotNull(sent).getValue("description").jsonPrimitive.content)
        assertNull(model.token?.scopes)
        assertTrue(model.editorCompleted, "Acknowledged edits close the token editor")
    }
    @Test fun `another accounts token metadata cannot be edited by a replayed local model`() = runTest {
        val model = SystemTokensModel(id = 42, admin = true, currentPrincipalId = "other", token = token())
        model.save("Name", "", "[]", "__self__", "", context { _, _ -> error("Must not execute") })
        assertTrue(model.feedback.failed)
        assertTrue("owner" in model.feedback.message)
    }
    @Test fun `unconfirmed token actions discard one-time secret and never call the API`() = runTest {
        val model = SystemTokensModel(currentPrincipalId = "owner", rawToken = "one-time-value")
        model.change(42, "revoke", false, context { _, _ -> error("Must not execute") })
        assertTrue(model.feedback.failed)
        assertEquals("", model.rawToken)
    }
    @Test fun `persona failure preserves selected subsystems and entered text`() = runTest {
        val model = SystemPersonasModel(admin = true)
        model.save("Persona", "Unsaved", "[\"git\",\"system\",\"cms\"]", true, context { _, _ -> error("Backend unavailable") })
        assertTrue(model.feedback.failed)
        assertEquals("Backend unavailable", model.feedback.message)
        assertEquals("Unsaved", model.draft.description)
        assertEquals(listOf("git", "system", "cms"), model.draft.subsystems)
    }
}
