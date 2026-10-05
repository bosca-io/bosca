package bosca.ksp.generator.db

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundRepository
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

class RepositoryProviderGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundRepository>(codeGenerator) {

    override fun generate(items: Collection<FoundRepository>) {
        items.forEach { (repository, provider, provides, classDeclaration) ->
            val classBuilder = TypeSpec
                .classBuilder(provider)
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addSuperinterface(Types.ObjectProvider.parameterizedBy(repository))
                .addAnnotation(AnnotationSpec.builder(Provider::class).addMember("singleton = true").build())
                .addOriginatingKSFile(classDeclaration.containingFile ?: error("No containing file"))

            classBuilder.addProperty(
                PropertySpec
                    .builder("type", KClass::class.asClassName().parameterizedBy(repository), KModifier.OVERRIDE)
                    .initializer("%T::class", repository)
                    .build()
            )

            classBuilder.addFunction(
                FunSpec
                    .builder("get")
                    .addCode("return %T()", provides)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(repository)
                    .build()
            )

            FileSpec.builder(provider)
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addType(classBuilder.build())
                .build()
                .writeTo(codeGenerator, false)
        }
    }
}