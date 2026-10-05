package bosca.ksp

import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.generator.service.ServiceProviderGenerator
import bosca.ksp.visitors.ServiceVisitor
import bosca.service.annotation.ServiceImplementation
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSVisitorVoid

class BoscaServiceSymbolProcessor(
    codeGenerator: CodeGenerator,
    logger: KSPLogger,
    providerRegistrarPrefix: String
) : AbstractBoscaSymbolProcessor(codeGenerator, logger, providerRegistrarPrefix) {

    private val serviceVisitor = ServiceVisitor(processed, logger)
    private val serviceGenerators = listOf(
        ServiceProviderGenerator(codeGenerator, logger),
    )

    override val allGenerators = serviceGenerators

    override fun newVisitors(resolver: Resolver): Array<Pair<Map<Boolean, List<KSAnnotated>>, KSVisitorVoid>> = arrayOf(
        Pair(resolver.resolve(ServiceImplementation::class), serviceVisitor),
    )

    override fun canBeEmpty(generator: AbstractGenerator<*>?) = false

    override fun incrementalGenerate(finish: Boolean) {
        serviceVisitor.generate(serviceGenerators)
    }
}

class BoscaServiceSymbolProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): BoscaServiceSymbolProcessor {
        var prefix = environment.options["ProviderRegistrarPrefix"] ?: ""
        if (environment.options["isTest"]?.toBoolean() ?: false) {
            prefix = "Test$prefix"
        }
        return BoscaServiceSymbolProcessor(
            environment.codeGenerator,
            environment.logger,
            prefix
        )
    }
}