@file:Suppress("JvmTaintAnalysis")

package bosca.scripting.host

import bosca.di.ConfigurationProviderRegistrar
import bosca.di.ContentProviderRegistrar
import bosca.di.CoreContentProviderRegistrar
import bosca.di.CoreProviderRegistrar
import bosca.di.ProviderRegistry.register
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.engine.KtsEngine
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.system.exitProcess

@OptIn(DelicateCoroutinesApi::class, InternalDI::class)
fun main(vararg args: String) {
    if (args.isEmpty()) {
        println("usage: <script file>")
        return
    }
    val scriptFile = Path.of(args[0]).toFile()
    if (!scriptFile.exists()) {
        println("Script file not found: ${args[0]}")
        return
    }
    register(CoreProviderRegistrar())
    register(CoreContentProviderRegistrar())
    register(ContentProviderRegistrar())
    register(ConfigurationProviderRegistrar())
    provides(singleton = true) {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
    runBlocking {
        val engine = KtsEngine()
        val script = engine.compile<Any>(scriptFile.name, 1, scriptFile.readText())
        val configuredJson = bosca.di.provide<Json>()
        val context = DefaultScriptContext(
            authentication = AuthenticationContext(null, null),
            scope = this,
            json = configuredJson,
        )
        val result = script.execute(context)
        if (result != null && result != Unit) {
            println("Result: $result")
        }
    }
    exitProcess(0)
}
