package bosca.scripting.context

import bosca.security.service.AuthenticationContext
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BoscaScriptContextTest {

    private val authentication = mockk<AuthenticationContext>()
    private val scope = TestScope()
    private val json = Json

    @Test
    fun `implements ScriptContext`() {
        val ctx = DefaultScriptContext(authentication, scope, json = json)
        assertIs<ScriptContext>(ctx)
    }

    @Test
    fun `default input is JsonNull`() {
        val ctx = DefaultScriptContext(authentication, scope, json = json)
        assertEquals(JsonNull, ctx.input)
    }

    @Test
    fun `input is accessible`() {
        val input = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val ctx = DefaultScriptContext(authentication, scope, input, json)
        assertEquals(input, ctx.input)
    }

    @Test
    fun `authentication is accessible`() {
        val ctx = DefaultScriptContext(authentication, scope, json = json)
        assertEquals(authentication, ctx.authentication)
    }
}
