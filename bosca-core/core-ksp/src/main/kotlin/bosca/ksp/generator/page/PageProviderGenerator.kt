package bosca.ksp.generator.page

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundPageController
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

class PageProviderGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundPageController>(codeGenerator) {

    override fun generate(items: Collection<FoundPageController>) {
        items.forEach { (page, pageProvider, classDeclaration) ->
            val classBuilder = TypeSpec
                .classBuilder(pageProvider)
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addSuperinterface(Types.ObjectProvider.parameterizedBy(page))
                .addAnnotation(AnnotationSpec.builder(Provider::class).addMember("singleton = true").build())
                .addOriginatingKSFile(classDeclaration.containingFile ?: error("No containing file"))

            val argumentsCode = buildString {
                classDeclaration.primaryConstructor?.parameters?.forEach {
                    val name = it.type.toTypeName()
                    append("${it.name?.asString()} = ")
                    if (name is ParameterizedTypeName && name.rawType == Types.ObjectProvider) {
                        append("ProviderRegistry.getProvider()")
                    } else {
                        append("ProviderRegistry.get()")
                    }
                    append(",\n")
                }
            }

            classBuilder.addProperty(
                PropertySpec
                    .builder("type", KClass::class.asClassName().parameterizedBy(page), KModifier.OVERRIDE)
                    .initializer("%T::class", page)
                    .build()
            )

            classBuilder.addFunction(
                FunSpec
                    .builder("get")
                    .addCode("return %T($argumentsCode)", page)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(page)
                    .build()
            )

            FileSpec.builder(pageProvider)
                .addType(classBuilder.build())
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addImport(Types.ProviderRegistry, "")
                .build()
                .writeTo(codeGenerator, true)
        }
    }
}