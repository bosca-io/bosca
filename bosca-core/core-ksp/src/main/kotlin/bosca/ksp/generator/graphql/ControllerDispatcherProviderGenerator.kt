package bosca.ksp.generator.graphql

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundTypeController
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo
import kotlin.reflect.KClass

class ControllerDispatcherProviderGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundTypeController>(codeGenerator) {

    override fun generate(items: Collection<FoundTypeController>) {
        items.forEach { (_, _, _, _, dispatcher, provider, classDeclaration) ->
            val classBuilder = TypeSpec
                .classBuilder(provider)
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addAnnotation(AnnotationSpec.builder(Provider::class).addMember("singleton = true").build())
                .addSuperinterface(Types.ObjectProvider.parameterizedBy(dispatcher))
                .addOriginatingKSFile(classDeclaration.containingFile ?: error("No containing file"))

            classBuilder.addProperty(
                PropertySpec
                    .builder("type", KClass::class.asClassName().parameterizedBy(dispatcher), KModifier.OVERRIDE)
                    .initializer("%T::class", dispatcher)
                    .build()
            )

            classBuilder.addFunction(
                FunSpec
                    .builder("get")
                    .addCode("return %T(ProviderRegistry.get())", dispatcher)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(dispatcher)
                    .build()
            )

            FileSpec.builder(provider)
                .addType(classBuilder.build())
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addImport(Types.ProviderRegistry, "")
                .build()
                .writeTo(codeGenerator, true)
        }
    }
}