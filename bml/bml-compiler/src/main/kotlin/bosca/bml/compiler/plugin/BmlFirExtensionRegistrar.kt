@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package bosca.bml.compiler.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/** Wires the BML FIR declaration generator into the compiler's FIR pipeline. */
class BmlFirExtensionRegistrar(private val sourceRoots: List<String>) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> BmlFirDeclarationGenerator(session, sourceRoots) }
    }
}
