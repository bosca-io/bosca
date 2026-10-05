package bosca.ksp

import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.generator.di.ProvidersProviderGenerator
import bosca.ksp.generator.di.ProvidersRegistrarGenerator
import bosca.ksp.visitors.FoundProvider
import bosca.ksp.visitors.ProviderVisitor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSVisitorVoid

class BoscaDISymbolProcessor(
    codeGenerator: CodeGenerator,
    logger: KSPLogger,
    providerRegistrarPrefix: String,
    enableSerializersRegistrar: Boolean,
    enableSerializerRegistrar: Boolean
) : AbstractBoscaSymbolProcessor(codeGenerator, logger, providerRegistrarPrefix) {

    private val allProviders = mutableSetOf<FoundProvider>()
    private val providerVisitor = ProviderVisitor(processed, logger, allProviders)

    private val providers = ProvidersProviderGenerator(codeGenerator, allProviders::add)
    private val providersRegistrar = ProvidersRegistrarGenerator(codeGenerator, providerRegistrarPrefix, enableSerializersRegistrar, enableSerializerRegistrar)
    private val providerGenerators = listOf(providers)
    override val allGenerators = providerGenerators

    override fun newVisitors(resolver: Resolver): Array<Pair<Map<Boolean, List<KSAnnotated>>, KSVisitorVoid>> = arrayOf(
        Pair(resolver.resolve(bosca.di.annotation.Provider::class), providerVisitor),
        Pair(resolver.resolve(bosca.di.annotation.Providers::class), providerVisitor),
    )

    override fun canBeEmpty(generator: AbstractGenerator<*>?) = false

    override fun incrementalGenerate(finish: Boolean) {
        providerVisitor.generate(providerGenerators)
        if (finish) {
            providersRegistrar.generate(allProviders)
            allProviders.clear()
        }
    }
}

class BoscaDISymbolProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): BoscaDISymbolProcessor {
        var prefix = environment.options["ProviderRegistrarPrefix"] ?: ""
        val enableSerializersRegistrar = environment.options["enableSerializersRegistrar"]?.toBoolean() ?: true
        val enableSerializerRegistrar = environment.options["enableSerializerRegistrar"]?.toBoolean() ?: true
        if (environment.options["isTest"]?.toBoolean() ?: false) {
            prefix = "Test$prefix"
        }
        return BoscaDISymbolProcessor(
            environment.codeGenerator,
            environment.logger,
            prefix,
            enableSerializersRegistrar,
            enableSerializerRegistrar
        )
    }
}
