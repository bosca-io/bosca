@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package bosca.analytics.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object AnalyticsConfigurationKeys {
    val ENABLED = CompilerConfigurationKey<Boolean>("analytics.enabled")
    val VERBOSE = CompilerConfigurationKey<Boolean>("analytics.verbose")
}

class AnalyticsCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption("enabled", "<true|false>", "Enable analytics auto-instrumentation", required = false),
        CliOption("verbose", "<true|false>", "Report every instrumented declaration", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "enabled" -> configuration.put(AnalyticsConfigurationKeys.ENABLED, value.toBooleanStrictOrNull() ?: true)
            "verbose" -> configuration.put(AnalyticsConfigurationKeys.VERBOSE, value.toBooleanStrictOrNull() ?: false)
        }
    }

    companion object {
        const val PLUGIN_ID = "bosca.analytics"
    }
}
