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
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import kotlin.reflect.KClass

class ControllerProviderGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundTypeController>(codeGenerator) {

    override fun generate(items: Collection<FoundTypeController>) {
        items.forEach { (_, _, controller, provider, _, _, classDeclaration) ->
            val classBuilder = TypeSpec
                .classBuilder(provider)
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addSuperinterface(Types.ObjectProvider.parameterizedBy(controller))
                .addAnnotation(AnnotationSpec.builder(Provider::class).addMember("singleton = true").build())
                .addOriginatingKSFile(classDeclaration.containingFile ?: error("No containing file"))

            val argumentsCode = buildString {
                classDeclaration.primaryConstructor?.parameters?.forEach {
                    val name = it.type.toTypeName()
                    append("${it.name?.asString()} = ")

                    val providerName = it.annotations.find { annotation -> annotation.shortName.asString() == "ProviderName" }?.arguments?.firstOrNull()?.value as? String
                    append(if (providerName != null) {
                        if (name is ParameterizedTypeName && name.rawType == Types.ObjectProvider) "ProviderRegistry.getProvider(\"$providerName\")"
                        else "ProviderRegistry.get(\"$providerName\")"
                    } else {
                        if (name is ParameterizedTypeName && name.rawType == Types.ObjectProvider) "ProviderRegistry.getProvider()"
                        else "ProviderRegistry.get()"
                    })
                    append(",\n")
                }
            }

            classBuilder.addProperty(
                PropertySpec
                    .builder("type", KClass::class.asClassName().parameterizedBy(controller), KModifier.OVERRIDE)
                    .initializer("%T::class", controller)
                    .build()
            )

            classBuilder.addFunction(
                FunSpec
                    .builder("get")
                    .addCode("return %T($argumentsCode)", controller)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(controller)
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