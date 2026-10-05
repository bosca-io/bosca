package bosca.ksp.generator.service

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.di.annotation.Provides
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundService
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
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

class ServiceProviderGenerator(codeGenerator: CodeGenerator, private val logger: KSPLogger) : AbstractGenerator<FoundService>(codeGenerator) {

    override fun generate(items: Collection<FoundService>) {
        items.forEach { (service, implementation, provider, classDeclaration) ->
            logger.info("Generating provider for $service")
            val classBuilder = TypeSpec
                .classBuilder(provider)
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addSuperinterface(Types.ObjectProvider.parameterizedBy(service))
                .addAnnotation(AnnotationSpec.builder(Provider::class).addMember("singleton = true").build())
                .addAnnotation(
                    AnnotationSpec.builder(Provides::class)
                        .addMember(
                            "type = %T::class",
                            if (service is ParameterizedTypeName) service.rawType else service,
                        )
                        .build()
                )
                .addOriginatingKSFile(classDeclaration.containingFile ?: error("No containing file"))

            classBuilder.addProperty(
                PropertySpec
                    .builder("type", KClass::class.asClassName().parameterizedBy(service), KModifier.OVERRIDE)
                    .initializer("%T::class", service)
                    .build()
            )

            classBuilder.addFunction(
                FunSpec
                    .builder("get")
                    .addCode(
                        "return %T(${
                            classDeclaration.primaryConstructor?.parameters?.joinToString {
                                val providerName = it.annotations.find { annotation -> annotation.shortName.asString() == "ProviderName" }?.arguments?.firstOrNull()?.value as? String
                                val name = it.type.toTypeName()
                                if (providerName != null) {
                                    if (name is ParameterizedTypeName && name.rawType == Types.ObjectProvider) "ProviderRegistry.getProvider(\"$providerName\")"
                                    else "ProviderRegistry.get(\"$providerName\")"
                                } else {
                                    if (name is ParameterizedTypeName && name.rawType == Types.ObjectProvider) "ProviderRegistry.getProvider()"
                                    else "ProviderRegistry.get()"
                                }
                            }
                        })", implementation
                    )
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(service)
                    .build()
            )

            FileSpec.builder(provider)
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addImport(Types.ProviderRegistry, "")
                .addType(classBuilder.build())
                .build()
                .writeTo(codeGenerator, false)
        }
    }
}
