package bosca.scripting.engine.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.scripting.engine.Engine
import bosca.scripting.engine.KtsEngine
import bosca.scripting.engine.ScriptSourceValidator
import bosca.scripting.engine.ScriptingSecurityConfiguration
import bosca.scripting.repository.CompiledScriptRepository
import bosca.scripting.service.LocalScriptExecutionServiceImpl
import bosca.scripting.service.ScriptExecutionService

@Providers
class Configuration {

    @Provider(singleton = true, name = "local-engine")
    fun engine(
        config: ScriptingSecurityConfiguration,
        compiledScriptRepository: CompiledScriptRepository,
        validator: ScriptSourceValidator,
    ): Engine = KtsEngine(config, validator, compiledScriptRepository)

    @Provider(singleton = true, name = "local-execution-service")
    fun scriptExecutionService(engine: Engine): ScriptExecutionService =
        LocalScriptExecutionServiceImpl(engine)
}
