package bosca.scripting.context

import bosca.security.service.AuthenticationContext
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TriggerContextTest {

    private val authentication = mockk<AuthenticationContext>()
    private val scope = TestScope()
    private val json = Json

    @Test
    fun `extends BoscaScriptContext`() {
        val ctx = TriggerContext(
            authentication = authentication,
            scope = scope,
            json = json,
            eventName = "test.event",
            eventPayload = JsonObject(emptyMap())
        )
        assertIs<BoscaScriptContext>(ctx)
    }

    @Test
    fun `eventName is accessible`() {
        val ctx = TriggerContext(
            authentication = authentication,
            scope = scope,
            json = json,
            eventName = "content.created",
            eventPayload = JsonObject(emptyMap())
        )
        assertEquals("content.created", ctx.eventName)
    }

    @Test
    fun `eventPayload is accessible`() {
        val payload = JsonObject(mapOf("id" to JsonPrimitive("123")))
        val ctx = TriggerContext(
            authentication = authentication,
            scope = scope,
            json = json,
            eventName = "test",
            eventPayload = payload
        )
        assertEquals(payload, ctx.eventPayload)
    }

    @Test
    fun `authentication is accessible`() {
        val ctx = TriggerContext(
            authentication = authentication,
            scope = scope,
            json = json,
            eventName = "test",
            eventPayload = JsonObject(emptyMap())
        )
        assertEquals(authentication, ctx.authentication)
    }
}
