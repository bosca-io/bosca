package bosca.bml.codegen

import bosca.bml.library.BmlImportResolver

/** Shared direct provider composition for K2 and standalone Kotlin generation. */
internal object BmlProviderRegistryGenerator {
    fun registrars(imports: List<BmlImportResolver.Binding>, local: List<String> = emptyList()): List<String> =
        (imports.sortedBy { it.dependency.manifest.library }.flatMap { binding ->
            binding.graph.librariesFor(binding.dependency.manifest.library)
        }.distinctBy { it.manifest.library }.flatMap { it.manifest.providerRegistrars } + local.sorted()).distinct()

    fun generate(packageName: String, registrars: List<String>): String = buildString {
        appendLine("package $packageName")
        appendLine("/** Direct library provider composition for this module. Generated; do not edit. */")
        appendLine("public object BmlProviders : bosca.di.ProviderRegistrar {")
        appendLine("    public val all: List<bosca.di.ProviderRegistrar> = listOf(")
        registrars.forEach { appendLine("        $it(),") }
        appendLine("    )")
        appendLine("    override fun register() { bosca.di.register(*all.toTypedArray()) }")
        appendLine("}")
    }
}
