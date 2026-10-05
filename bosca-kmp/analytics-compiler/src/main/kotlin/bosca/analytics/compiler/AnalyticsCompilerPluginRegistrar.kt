@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package bosca.analytics.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration

class AnalyticsCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = AnalyticsCommandLineProcessor.PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (!configuration.get(AnalyticsConfigurationKeys.ENABLED, true)) return
        IrGenerationExtension.registerExtension(
            AnalyticsIrGenerationExtension(
                messages = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE),
                verbose = configuration.get(AnalyticsConfigurationKeys.VERBOSE, false),
            ),
        )
    }
}
