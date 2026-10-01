@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.service

import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.engine.BoscaCompiledScript
import bosca.scripting.engine.KtsEngine
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptType
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class LocalScriptExecutionServiceImplTest {

    private val engine = mockk<KtsEngine>()
    private val service = LocalScriptExecutionServiceImpl(engine)

    private val testScript = Script(
        id = Uuid.random(),
        key = "exec-test",
        name = "Exec Test",
        source = "\"result\"",
        type = ScriptType.GENERAL,
        version = 1
    )

    @Test
    fun `execute compiles and runs script returning JsonElement`() = runTest {
        val compiled = mockk<BoscaCompiledScript<Any>>()
        val expectedResult = JsonPrimitive("result")
        val context = DefaultScriptContext(AuthenticationContext(null, null), this, json = Json)

        coEvery { engine.compile<Any>(testScript.key, testScript.version, testScript.source) } returns compiled
        coEvery { compiled.execute(any()) } returns expectedResult

        val result = service.execute<Any>(testScript, context)
        assertEquals(expectedResult, result)
        coVerify { engine.compile<Any>("exec-test", 1, "\"result\"") }
        coVerify { compiled.execute(any()) }
    }

    @Test
    fun `execute returns null when script returns non-JsonElement`() = runTest {
        val compiled = mockk<BoscaCompiledScript<Any>>()
        val context = DefaultScriptContext(AuthenticationContext(null, null), this, json = Json)

        coEvery { engine.compile<Any>(any(), any(), any()) } returns compiled
        coEvery { compiled.execute(any()) } returns "plain string"

        val result = service.execute<Any>(testScript, context)
        assertEquals("plain string", result)
    }

    @Test
    fun `execute returns null when script returns null`() = runTest {
        val compiled = mockk<BoscaCompiledScript<Any>>()
        val context = DefaultScriptContext(AuthenticationContext(null, null), this, json = Json)

        coEvery { engine.compile<Any>(any(), any(), any()) } returns compiled
        coEvery { compiled.execute(any()) } returns null

        val result = service.execute<Any>(testScript, context)
        assertNull(result)
    }

    @Test
    fun `invalidateCache delegates to engine`() {
        io.mockk.every { engine.invalidate("key", 1) } returns Unit
        service.invalidateCache("key", 1)
        verify { engine.invalidate("key", 1) }
    }

    @Test
    fun `invalidateAllCaches delegates to engine`() {
        io.mockk.every { engine.invalidateAll() } returns Unit
        service.invalidateAllCaches()
        verify { engine.invalidateAll() }
    }
}
