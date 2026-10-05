package bosca.bml.compiler.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * IR phase. Fills the bodies of the compiler-generated render and
 * dispatch members for BML pages/components/contracts/islands. Each generated
 * class carries `@BmlGenerated(source = "…")`; this extension re-parses that
 * source `.bml` via the bml-compiler parser ([bosca.bml.parser.BmlParser]) and
 * lowers its markup AST into the render body.
 *
 * Mirrors `~/git/engine` Volt's `VoltIrGenerationExtension` (collect → transform).
 * Body lowering is implemented incrementally.
 */
class BmlIrGenerationExtension(
    private val configuration: CompilerConfiguration,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        // Collect @BmlGenerated classes, re-parse their .bml sources, emit bodies.
        // (incremental.)
    }
}
