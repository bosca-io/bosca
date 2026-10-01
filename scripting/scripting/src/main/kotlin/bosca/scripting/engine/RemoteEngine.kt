package bosca.scripting.engine

/**
 * Engine implementation selected when the Kotlin scripting compiler is excluded
 * from the build (e.g. bosca-server with `-Pbosca.scripting.engine=false`).
 *
 * `validate()` runs the in-process [ScriptSourceValidator] regex check, which has
 * no dependency on the scripting compiler. Compilation and execution are handled
 * remotely by a worker via [bosca.scripting.service.RemoteScriptExecutionServiceImpl],
 * so `compile()` is unreachable here — it throws if called.
 */
internal class RemoteEngine(
    private val validator: ScriptSourceValidator,
) : Engine {

    override suspend fun validate(script: String) {
        validator.validate(script)
    }

    override fun invalidate(key: String, version: Int) {
    }

    override fun invalidateAll() {
    }

    override suspend fun <T> compile(
        key: String,
        version: Int,
        source: String,
    ): BoscaCompiledScript<T> {
        error("compile() unreachable: server is in remote execution mode")
    }
}
