package bosca.analytics.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class AnalyticsIrGenerationExtension(
    private val messages: MessageCollector,
    private val verbose: Boolean,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.files.forEach { file ->
            file.transformChildrenVoid(
                AnalyticsTransformer(
                    context = pluginContext,
                    hooks = pluginContext.analyticsHooks(file),
                    messages = messages,
                    verbose = verbose,
                ),
            )
        }
    }
}
