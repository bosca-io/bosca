package bosca.atlas.server

import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.scripting.engine.KtsEngine
import bosca.scripting.service.LocalScriptExecutionServiceImpl
import bosca.scripting.service.ScriptExecutionService

/** Runs Atlas's real script routes with the existing JVM engine instead of an external engine worker. */
@OptIn(InternalDI::class)
fun main(args: Array<String>) = startAtlasServer(args) {
    provides<ScriptExecutionService>(name = "local-execution-service") { LocalScriptExecutionServiceImpl(KtsEngine()) }
}
