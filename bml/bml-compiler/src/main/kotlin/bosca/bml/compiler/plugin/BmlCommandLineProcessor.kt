@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package bosca.bml.compiler.plugin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** Compiler-configuration keys for the BML K2 plugin. */
object BmlConfigKeys {
    val ENABLED = CompilerConfigurationKey<Boolean>("bml.enabled")
    val PACKAGE_NAME = CompilerConfigurationKey<String>("bml.packageName")
    val LIBRARY_ID = CompilerConfigurationKey<String>("bml.libraryId")
    val LIBRARY_VERSION = CompilerConfigurationKey<String>("bml.libraryVersion")
    /** Bundled inline CSS keyed by original BML source location. */
    val LIBRARY_STYLES = CompilerConfigurationKey<String>("bml.libraryStyles")
    val LIBRARY_EXPORTS = CompilerConfigurationKey<List<String>>("bml.libraryExports")
    val LIBRARY_PROVIDER_REGISTRARS = CompilerConfigurationKey<List<String>>("bml.libraryProviderRegistrars")
    /** Resolved binary dependency artifacts containing optional BML manifests. */
    val LIBRARY_DEPENDENCIES = CompilerConfigurationKey<List<String>>("bml.libraryDependencies")
    /** Designated module-wide import file; defaults to imports.bml in the first source root. */
    val IMPORTS_FILE = CompilerConfigurationKey<String>("bml.importsFile")

    /** Root directories of `.bml` sources the plugin reads to synthesize page objects. */
    val SOURCE_ROOTS = CompilerConfigurationKey<List<String>>("bml.sourceRoots")

    /** Output directory for generated client TypeScript (`<script client>` / islands). */
    val TS_OUTPUT_DIR = CompilerConfigurationKey<String>("bml.tsOutputDir")

    /**
     * Output directory for the generated Kotlin (`.kt` + `.kt.map`). When set, the plugin also writes each
     * synthesized source to disk **and tags the compiled source with that real `.kt` path** — so the generated
     * code is inspectable, compiler errors point at it with correct line numbers, and a debugger can step it.
     */
    val KOTLIN_OUTPUT_DIR = CompilerConfigurationKey<String>("bml.kotlinOutputDir")

    /**
     * Output directory for generated resources. When set and the module compiles `<email>` units, the
     * plugin writes the email manifest (`META-INF/bml/message-manifest.json`) here; the
     * Gradle plugin adds the directory to the main resource set so the manifest lands in the jar — a
     * host can then list a jar's templates by reading the entry, without classloading.
     */
    val RESOURCES_OUTPUT_DIR = CompilerConfigurationKey<String>("bml.resourcesOutputDir")

    /**
     * Output directory for the site metrics manifest: `manifest.tsv` (per-page asset
     * closure) plus a `css/<tag>.css` chunk per styled component, so the Gradle `bmlMetrics` task
     * can report per-page first-load payload sizes without compiling anything itself.
     */
    val METRICS_OUTPUT_DIR = CompilerConfigurationKey<String>("bml.metricsOutputDir")
}

/**
 * Parses `-P plugin:bml:<option>=<value>` command-line options. Registered via
 * `META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor`.
 */
class BmlCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption("enabled", "<true|false>", "Enable BML compiler-plugin codegen", required = false),
        CliOption("bmlPackageName", "<package>", "Package for all generated Kotlin declarations", required = false),
        CliOption("bmlLibraryId", "<group:artifact>", "Compile a component library with this Maven identity", required = false),
        CliOption("bmlLibraryVersion", "<version>", "Version of the compiled component library", required = false),
        CliOption("bmlLibraryStyles", "<file>", "Prepared library stylesheet bodies", required = false),
        CliOption("bmlLibraryExport", "<tag>", "Export a component tag", required = false, allowMultipleOccurrences = true),
        CliOption("bmlLibraryProviderRegistrar", "<class>", "Publish a ProviderRegistrar constructor", required = false, allowMultipleOccurrences = true),
        CliOption("bmlLibraryDependency", "<file>", "Resolved binary library artifact", required = false, allowMultipleOccurrences = true),
        CliOption("bmlLibraryClasspath", "<classpath>", "Resolved binary library classpath", required = false),
        CliOption("bmlImportsFile", "<file>", "Module-wide BML imports", required = false),
        CliOption(
            "bmlSourceRoot", "<dir>", "Root directory of .bml sources (repeatable)",
            required = false, allowMultipleOccurrences = true,
        ),
        CliOption(
            "bmlTsOutputDir", "<dir>", "Output directory for generated client TypeScript",
            required = false,
        ),
        CliOption(
            "bmlKotlinOutputDir", "<dir>", "Output directory for the generated Kotlin (.kt + .kt.map)",
            required = false,
        ),
        CliOption(
            "bmlResourcesOutputDir", "<dir>", "Output directory for generated resources (the email manifest)",
            required = false,
        ),
        CliOption(
            "bmlMetricsOutputDir", "<dir>", "Output directory for the site metrics manifest + CSS chunks",
            required = false,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "enabled" -> configuration.put(BmlConfigKeys.ENABLED, value.toBooleanStrictOrNull() ?: true)
            "bmlPackageName" -> configuration.put(BmlConfigKeys.PACKAGE_NAME, value)
            "bmlLibraryId" -> configuration.put(BmlConfigKeys.LIBRARY_ID, value)
            "bmlLibraryVersion" -> configuration.put(BmlConfigKeys.LIBRARY_VERSION, value)
            "bmlLibraryStyles" -> configuration.put(BmlConfigKeys.LIBRARY_STYLES, value)
            "bmlLibraryExport" -> configuration.add(BmlConfigKeys.LIBRARY_EXPORTS, value)
            "bmlLibraryProviderRegistrar" -> configuration.add(BmlConfigKeys.LIBRARY_PROVIDER_REGISTRARS, value)
            "bmlLibraryDependency" -> configuration.add(BmlConfigKeys.LIBRARY_DEPENDENCIES, value)
            "bmlLibraryClasspath" -> value.split(java.io.File.pathSeparator).filter { it.isNotBlank() }
                .forEach { configuration.add(BmlConfigKeys.LIBRARY_DEPENDENCIES, it) }
            "bmlImportsFile" -> configuration.put(BmlConfigKeys.IMPORTS_FILE, value)
            "bmlSourceRoot" -> configuration.add(BmlConfigKeys.SOURCE_ROOTS, value)
            "bmlTsOutputDir" -> configuration.put(BmlConfigKeys.TS_OUTPUT_DIR, value)
            "bmlKotlinOutputDir" -> configuration.put(BmlConfigKeys.KOTLIN_OUTPUT_DIR, value)
            "bmlResourcesOutputDir" -> configuration.put(BmlConfigKeys.RESOURCES_OUTPUT_DIR, value)
            "bmlMetricsOutputDir" -> configuration.put(BmlConfigKeys.METRICS_OUTPUT_DIR, value)
        }
    }

    companion object {
        const val PLUGIN_ID = "bml"

        /** The `.bml` source file extension (no dot). Shared with the Gradle plugin + file-type registration. */
        const val BML_EXTENSION = "bml"
    }
}
