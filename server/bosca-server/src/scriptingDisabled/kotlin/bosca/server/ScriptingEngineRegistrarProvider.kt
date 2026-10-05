package bosca.server

import bosca.di.ProviderRegistrar

/**
 * `scriptingDisabled` source-set variant. Selected by `build.gradle.kts` when
 * `-Pbosca.scripting.engine=false`, where the scripting-engine module is excluded
 * from the classpath and `bosca.di.ScriptingEngineProviderRegistrar` does not exist.
 *
 * Returns `null` so `Application.kt` skips registration. The runtime then falls back
 * to `RemoteEngine` (in-process source validation only) and delegates compile /
 * execute to a worker via `RemoteScriptExecutionServiceImpl`.
 */
object ScriptingEngineRegistrarProvider {
    fun get(): ProviderRegistrar? = null
}
