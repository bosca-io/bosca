package bosca.ksp.generator.cache

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundCacheSerializer
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class CacheSerializersRegistrarGenerator(
    codeGenerator: CodeGenerator,
    private val prefix: String
) : AbstractGenerator<FoundCacheSerializer>(codeGenerator) {

    override fun generate(items: Collection<FoundCacheSerializer>) {
        val serializersType = TypeSpec
            .classBuilder("${prefix}SerializersRegistrar")
            .addSuperinterface(Types.ProviderRegistrar)
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addFunction(
                FunSpec
                    .builder("register")
                    .addCode(items.joinToString("\n") { "bosca.cache.serializers.CacheKeyRegistry.register(%T::class, %S, %T)" }, *(items.flatMap { listOf(it.key, it.type, it.serializer) }.toTypedArray()))
                    .addCode("\n")
                    .addModifiers(KModifier.OVERRIDE)
                    .build()
            )
            .build()
        FileSpec.builder("bosca.di", "${prefix}SerializersRegistrar")
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
            .addType(serializersType)
            .build()
            .writeTo(codeGenerator, true, items.mapNotNull { it.classDeclaration.containingFile })
    }
}