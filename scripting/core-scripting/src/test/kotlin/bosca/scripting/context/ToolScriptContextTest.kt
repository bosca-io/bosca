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

class ToolScriptContextTest {

    private val authentication = mockk<AuthenticationContext>()
    private val scope = TestScope()
    private val json = Json

    @Test
    fun `extends BoscaScriptContext`() {
        val input = JsonObject(mapOf("key" to JsonPrimitive("val")))
        val ctx = ToolScriptContext(authentication, scope, input, json)
        assertIs<BoscaScriptContext>(ctx)
    }

    @Test
    fun `input is accessible`() {
        val input = JsonObject(mapOf("foo" to JsonPrimitive("bar")))
        val ctx = ToolScriptContext(authentication, scope, input, json)
        val inputObj = ctx.input as JsonObject
        assertEquals(JsonPrimitive("bar"), inputObj["foo"])
    }

    @Test
    fun `authentication is accessible`() {
        val input = JsonObject(emptyMap())
        val ctx = ToolScriptContext(authentication, scope, input, json)
        assertEquals(authentication, ctx.authentication)
    }

    @Test
    fun `scope is accessible`() {
        val input = JsonObject(emptyMap())
        val ctx = ToolScriptContext(authentication, scope, input, json)
        assertEquals(scope, ctx.scope)
    }
}
