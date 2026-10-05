package bosca.ksp

import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.VisitorConsumer
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import kotlin.reflect.KClass

abstract class AbstractBoscaSymbolProcessor(
    protected val codeGenerator: CodeGenerator,
    protected val logger: KSPLogger,
    protected val providerRegistrarPrefix: String
) : SymbolProcessor {

    protected val processed = mutableSetOf<Pair<String, String>>()

    protected abstract val allGenerators: List<AbstractGenerator<*>>

    protected abstract fun newVisitors(resolver: Resolver): Array<Pair<Map<Boolean, List<KSAnnotated>>, KSVisitorVoid>>

    protected fun Resolver.resolve(type: KClass<*>) = getSymbolsWithAnnotation(type.qualifiedName ?: error("No qualified name found for $type")).groupBy { it.validate() }

    private fun process(resolver: Resolver, resolverVisitors: Array<Pair<Map<Boolean, List<KSAnnotated>>, KSVisitorVoid>>): List<KSAnnotated> {
        try {
            allGenerators.forEach { it.prepare(resolver) }
            resolverVisitors.forEach { (resolved, visitor) ->
                resolved[true]?.forEach {
                    it.accept(visitor, Unit)
                }
            }
            return resolverVisitors.flatMap { (resolved, _) ->
                resolved[false] ?: emptyList()
            }
        } catch (e: Exception) {
            logger.error(e.stackTraceToString())
            return emptyList()
        }
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val visitors = newVisitors(resolver)
        val processed = process(resolver, visitors)
        incrementalGenerate(false)
        return processed
    }

    protected abstract fun incrementalGenerate(finish: Boolean)

    protected abstract fun canBeEmpty(generator: AbstractGenerator<*>?): Boolean

    protected fun <T> VisitorConsumer<T>.generate(generators: List<AbstractGenerator<T>>) {
        logger.info("Generating ${javaClass.simpleName}: ${generators.size} items...")
        try {
            val items = consume()
            if (items.isEmpty() && !canBeEmpty(generators.firstOrNull())) {
                return
            }
            generators.forEach { it.generate(items) }
        } catch (e: Exception) {
            logger.error("${e.message} - ${e.stackTraceToString()}")
        }
    }

    override fun finish() {
        incrementalGenerate(true)
        processed.clear()
    }
}
