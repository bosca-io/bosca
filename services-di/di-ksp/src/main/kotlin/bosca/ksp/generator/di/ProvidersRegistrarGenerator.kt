package bosca.ksp.generator.di

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundProvider
import bosca.ksp.visitors.ProviderBindingKey
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class ProvidersRegistrarGenerator(
    codeGenerator: CodeGenerator,
    private val prefix: String,
    private val enableSerializersRegistrar: Boolean,
    private val enableSerializerRegistrar: Boolean = true
) : AbstractGenerator<FoundProvider>(codeGenerator) {

    override fun generate(items: Collection<FoundProvider>) {
        val providers = linkedMapOf<ProviderBindingKey, FoundProvider>()
        items.forEach { provider ->
            val existing = providers[provider.bindingKey]
            if (existing == null) {
                providers[provider.bindingKey] = provider
            } else if (existing.provider != provider.provider) {
                error(
                    "Duplicate provider for ${provider.bindingKey.type}" +
                        provider.name.takeIf { it.isNotEmpty() }?.let { " named '$it'" }.orEmpty() +
                        ": ${existing.provider} and ${provider.provider}"
                )
            }
        }
        val types = providers.values.filter { it.name.isEmpty() }
        val names = providers.values.filter { it.name.isNotEmpty() }

        val dispatchersType = TypeSpec
            .classBuilder("${prefix}ProviderRegistrar")
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addSuperinterface(Types.ProviderRegistrar)
            .addFunction(
                FunSpec
                    .builder("register")
                    .also {
                        if (enableSerializerRegistrar) it.addCode("bosca.serialization.${prefix}SerializerRegistrar().register()\n")
                        if (enableSerializersRegistrar) it.addCode("${prefix}SerializersRegistrar().register()\n")
                    }
                    .addCode(types.joinToString("\n") { "ProviderRegistry.register(%T::class, %T(), %L)" }, *(types.flatMap { listOf(it.provides, it.provider, it.singleton) }.toTypedArray()))
                    .addCode("\n")
                    .addCode(names.joinToString("\n") {
                        if (it.provides is ParameterizedTypeName) {
                            "ProviderRegistry.register(%T::class as kotlin.reflect.KClass<%T>, %T(), %S, %L)"
                        } else {
                            "ProviderRegistry.register(%T::class, %T(), %S, %L)"
                        }
                    }, *(names.flatMap {
                        if (it.provides is ParameterizedTypeName) {
                            listOf(it.provides.rawType, it.provides, it.provider, it.name, it.singleton)
                        } else {
                            listOf(it.provides, it.provider, it.name, it.singleton)
                        }
                    }.toTypedArray()))
                    .addModifiers(KModifier.OVERRIDE)
                    .build()
            )
            .build()
        FileSpec.builder("bosca.di", "${prefix}ProviderRegistrar")
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\", \"USELESS_CAST\"").build())
            .addType(dispatchersType)
            .build()
            .writeTo(codeGenerator, true, items.mapNotNull { it.classDeclaration.containingFile })
    }
}
