package bosca.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver

abstract class AbstractGenerator<T>(protected val codeGenerator: CodeGenerator) {

    open fun prepare(resolver: Resolver) {
    }

    abstract fun generate(items: Collection<T>)
}