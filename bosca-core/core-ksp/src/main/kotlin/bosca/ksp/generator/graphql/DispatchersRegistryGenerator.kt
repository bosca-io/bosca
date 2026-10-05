package bosca.ksp.generator.graphql

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundTypeController
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.writeTo

class DispatchersRegistryGenerator(codeGenerator: CodeGenerator, val prefix: String) : AbstractGenerator<FoundTypeController>(codeGenerator) {

    override fun generate(items: Collection<FoundTypeController>) {
        val dispatchersByType = items.groupBy { it.typeName }
        val dispatcherCode = dispatchersByType.entries.joinToString(", ") { (_, controllers) ->
            if (controllers.size == 1) {
                "%S to %T(get())"
            } else {
                "%S to %T(listOf(${controllers.joinToString(", ") { "%T(get())" }}))"
            }
        }
        val dispatcherCodeArguments = dispatchersByType.flatMap { (typeName, controllers) ->
            if (controllers.size == 1) {
                listOf(typeName, controllers.single().dispatcher)
            } else {
                listOf(typeName, Types.CompositeDispatcher) + controllers.map { it.dispatcher }
            }
        }.toTypedArray()
        val dispatchersType = TypeSpec
            .classBuilder("${prefix}DispatchersRegistrar")
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addSuperinterface(Types.DispatchersRegistrar)
            .addFunction(
                FunSpec
                    .builder("dispatchers")
                    .returns(Map::class.asClassName().parameterizedBy(String::class.asClassName(), Types.Dispatcher))
                    .addCode("return mapOf($dispatcherCode)", *dispatcherCodeArguments)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .build()
            )
            .build()

        FileSpec.builder("bosca.graphql.dispatcher", "${prefix}DispatchersRegistry")
            .addType(dispatchersType)
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
            .addImport(Types.ProviderRegistry, "get")
            .build()
            .writeTo(codeGenerator, true, items.mapNotNull { it.classDeclaration.containingFile })
    }
}
