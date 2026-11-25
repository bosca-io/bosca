package bosca.scripting.service

import bosca.scripting.engine.Engine
import bosca.scripting.engine.KtsEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ScriptValidationTest {
    private val engine = KtsEngine()
    private val service = LocalScriptExecutionServiceImpl(engine)
    @AfterTest fun close() { engine.close() }

    @Test fun `compilation validates unsaved source without executing it`() = runBlocking {
        val result = service.validate("main { error(\"must not execute\") }", true)
        assertTrue(result.valid, result.errors.toString())
        assertTrue(result.compiled)
    }
    @Test fun `syntax diagnostics and source security failures are distinct`() = runBlocking {
        val syntax = service.validate("this is invalid kotlin", true)
        assertFalse(syntax.valid); assertTrue(syntax.compiled); assertTrue(syntax.errors.isNotEmpty())
        val security = service.validate("main { System.exit(0) }", true)
        assertFalse(security.valid); assertFalse(security.compiled); assertTrue(security.errors.isNotEmpty())
    }
    @Test fun `source only checks do not claim compilation`() = runBlocking {
        val result = service.validate("this is invalid kotlin", false)
        assertTrue(result.valid); assertFalse(result.compiled)
    }
    @Test fun `infrastructure and cancellation are not source diagnostics`(): Unit = runBlocking {
        val failedEngine = mockk<Engine>(relaxed = true)
        val failedService = LocalScriptExecutionServiceImpl(failedEngine)
        coEvery { failedEngine.compile<Any>(any(), any(), any()) } throws IllegalStateException("compiler unavailable")
        assertFailsWith<IllegalStateException> { failedService.validate("main { null }", true) }
        coEvery { failedEngine.compile<Any>(any(), any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { failedService.validate("main { null }", true) }
    }
}
