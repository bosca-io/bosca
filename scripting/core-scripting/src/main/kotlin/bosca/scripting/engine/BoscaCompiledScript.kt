package bosca.scripting.engine

import bosca.scripting.context.ScriptContext

interface BoscaCompiledScript<T> {

    suspend fun execute(context: ScriptContext): T?
}