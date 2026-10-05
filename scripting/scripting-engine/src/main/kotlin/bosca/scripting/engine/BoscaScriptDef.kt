package bosca.scripting.engine

import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.scripting.context.BoscaScriptContext
import bosca.scripting.context.ScriptContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

@KotlinScript(
    displayName = "Bosca Script",
    fileExtension = "bosca.kts",
    compilationConfiguration = BoscaScriptCompilationConfiguration::class,
    evaluationConfiguration = BoscaScriptEvaluationConfiguration::class
)
abstract class BoscaScript(scriptContext: BoscaScriptContext) {

    val context: ScriptContext = scriptContext
    val authentication = scriptContext.authentication
    val scope = scriptContext.scope
    val log = LoggerFactory.getLogger(BoscaScript::class.java)

    fun <T> main(block: suspend () -> T): Deferred<T> {
        return scope.async {
            withConnectionManager {
                withRequestCache {
                    block()
                }
            }
        }
    }
}

object BoscaScriptCompilationConfiguration : ScriptCompilationConfiguration({
    baseClass(BoscaScript::class)
    defaultImports(
        "bosca.scripting.context.*",
        "bosca.di.provide",
        "bosca.scripting.engine.BoscaScript",
        "bosca.serialization.UUID",
        "kotlinx.serialization.json.*",
        "kotlinx.coroutines.*",
        "bosca.content.find.*",
        "bosca.content.collection.model.*",
        "bosca.content.collection.service.*",
        "bosca.content.metadata.model.*",
        "bosca.content.metadata.service.*",
        "bosca.content.transition.service.*"
    )
    compilerOptions("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    jvm {
        // Exposes the full server classpath at compile time so scripts can reference any class.
        // Runtime access is restricted by RestrictedClassLoader — scripts that compile against
        // disallowed classes will fail at runtime, not compile time.
        dependenciesFromCurrentContext(wholeClasspath = true, unpackJarCollections = true)
        jvmTarget("25")
    }
    ide {
        acceptedLocations(ScriptAcceptedLocation.Everywhere)
    }
}) {
    private fun readResolve(): Any = BoscaScriptCompilationConfiguration
}

object BoscaScriptEvaluationConfiguration : ScriptEvaluationConfiguration({}) {
    private fun readResolve(): Any = BoscaScriptEvaluationConfiguration
}
