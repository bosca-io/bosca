@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package bosca.bml.compiler.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.core.CoreFileTypeRegistry
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.CollectAdditionalSourceFilesExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.idea.KotlinFileType

/**
 * Registers the BML K2 plugin's extensions. Registered via
 * `META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`.
 *
 * The build path is a three-part pairing (all required; missing any one silently no-ops the rest):
 *  1. The Gradle plugin calls `KotlinSourceSet.addCustomSourceFilesExtensions("bml")` so KGP collects
 *     `.bml` files and hands them to the compiler.
 *  2. This registrar registers `.bml` -> [KotlinFileType] so the compiler's source validator accepts
 *     those entries (it requires `ext in {kt, kts}` OR `fileType == KotlinFileType`). This is also what
 *     makes [CollectAdditionalSourceFilesExtension] actually fire.
 *  3. [BmlAdditionalSourcesExtension] lowers each page `.bml` to Kotlin and contributes it as an
 *     in-memory source — no `.kt` files on disk.
 *
 * The FIR/IR extensions are reserved for IDE FIR synthesis (KEFS) and diagnostics/source-map work.
 */
class BmlCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = BmlCommandLineProcessor.PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (!configuration.get(BmlConfigKeys.ENABLED, true)) return
        val sourceRoots = configuration.getList(BmlConfigKeys.SOURCE_ROOTS)
        val messages = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)

        // (2) Teach the compiler that `.bml` is a Kotlin source file. registerExtensions runs during
        // environment setup, before source collection, so this is in place when validation happens.
        registerBmlFileType(FileTypeRegistry.getInstance(), messages)

        // (3) Generate Kotlin from `.bml` and contribute it as in-memory KtSourceFiles (no `.kt` on disk).
        CollectAdditionalSourceFilesExtension.registerExtension(BmlAdditionalSourcesExtension())
        // Reserved: IDE-only FIR synthesis (KEFS) + IR processing (diagnostics / source maps).
        FirExtensionRegistrarAdapter.registerExtension(BmlFirExtensionRegistrar(sourceRoots))
        IrGenerationExtension.registerExtension(BmlIrGenerationExtension(configuration))
    }
}

/**
 * Registers `.bml` -> [KotlinFileType] so the compiler's source validator accepts `.bml` entries (it
 * requires `ext in {kt, kts}` OR `fileType == KotlinFileType`), which is also what makes
 * [CollectAdditionalSourceFilesExtension] fire. Parameterized over the [registry] (rather than calling
 * `FileTypeRegistry.getInstance()` inline) so the defensive "unexpected registry type" arm is unit-testable;
 * in a real compile the registry is always a [CoreFileTypeRegistry].
 */
internal fun registerBmlFileType(registry: FileTypeRegistry?, messages: MessageCollector?) {
    if (registry is CoreFileTypeRegistry) {
        registry.registerFileType(KotlinFileType.INSTANCE, BmlCommandLineProcessor.BML_EXTENSION)
    } else {
        messages?.report(
            CompilerMessageSeverity.WARNING,
            "BML: could not register the .bml file type (registry=${registry?.let { it.javaClass.name }}); " +
                ".bml sources will not be compiled.",
        )
    }
}
