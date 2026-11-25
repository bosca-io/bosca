package bosca.scripting.service

import bosca.scripting.engine.Engine
import bosca.scripting.engine.ScriptCompilationException
import bosca.scripting.model.ScriptValidationResult
import bosca.serialization.UUID

/** Checks unsaved source without invoking the compiled script. */
suspend fun validateScriptSource(engine: Engine, source: String, compile: Boolean): ScriptValidationResult {
    try {
        engine.validate(source)
    } catch (failure: SecurityException) {
        return ScriptValidationResult(false, false, listOf(failure.message ?: "Source validation failed"))
    }
    if (!compile) return ScriptValidationResult(true, false, emptyList())
    val key = "__validate_${UUID.random()}"
    return try {
        engine.compile<Any>(key, 0, source)
        ScriptValidationResult(true, true, emptyList())
    } catch (failure: ScriptCompilationException) {
        ScriptValidationResult(false, true, failure.diagnostics)
    } finally {
        engine.invalidate(key, 0)
    }
}
