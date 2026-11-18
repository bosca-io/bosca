package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PasskeyActionsTest {
    private fun context(handler: suspend (String) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName))
    })
    @Test fun `removal uses self authorization and returns to sign in without using the revoked token again`() = runTest {
        val model = SystemPasskeysModel()
        model.delete("credential", true, context { operation ->
            assertEquals("SystemDeletePasskey", operation)
            Json.parseToJsonElement("""{"security":{"passkeys":{"delete":true}}}""")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertTrue(model.feedback.sessionRevoked)
        assertTrue(model.feedback.nextHref.startsWith("/login?"))
    }
    @Test fun `confirmation and last-credential failures never clear the current session`() = runTest {
        val model = SystemPasskeysModel()
        model.delete("credential", false, context { error("Must not execute") })
        assertTrue(model.feedback.failed)
        assertFalse(model.feedback.sessionRevoked)
        model.delete("credential", true, context { error("cannot remove the last credential from a principal") })
        assertTrue(model.feedback.failed)
        assertEquals("cannot remove the last credential from a principal", model.feedback.message)
        assertEquals("", model.feedback.nextHref)
        assertFalse(model.feedback.sessionRevoked)
    }
}
