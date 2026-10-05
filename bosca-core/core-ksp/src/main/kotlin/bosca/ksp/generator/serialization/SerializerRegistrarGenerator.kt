package bosca.ksp.generator.serialization

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundSerializableClass
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.writeTo

class SerializerRegistrarGenerator(
    codeGenerator: CodeGenerator,
    private val prefix: String
) : AbstractGenerator<FoundSerializableClass>(codeGenerator) {

    override fun generate(items: Collection<FoundSerializableClass>) {
        val registrarType = TypeSpec
            .classBuilder("${prefix}SerializerRegistrar")
            .addSuperinterface(Types.SerializerRegistrar)
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addFunction(buildRegisterFunction(items))
            .addFunction(buildGetClassNamesFunction(items))
            .build()

        FileSpec.builder("bosca.serialization", "${prefix}SerializerRegistrar")
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"")
                    .build()
            )
            .addType(registrarType)
            .build()
            .writeTo(codeGenerator, true, items.mapNotNull { it.classDeclaration.containingFile })

    }

    private fun buildRegisterFunction(items: Collection<FoundSerializableClass>): FunSpec {
        val builder = FunSpec.builder("register")
            .addModifiers(KModifier.OVERRIDE)
        for (item in items) {
            val assignableTypes = item.assignableTypes
                .map { CodeBlock.of("%T::class.java", it) }
            val assignableArguments = if (assignableTypes.isEmpty()) {
                CodeBlock.of("")
            } else {
                CodeBlock.of(", %L", assignableTypes.joinToCode(", "))
            }
            builder.addStatement(
                "%T.register(%T::class.java, %T.serializer()%L)",
                Types.SerializerCache,
                item.className,
                item.className,
                assignableArguments,
            )
        }
        return builder.build()
    }

    private fun buildGetClassNamesFunction(items: Collection<FoundSerializableClass>): FunSpec {
        val builder = FunSpec.builder("getSerializableClassNames")
            .addModifiers(KModifier.OVERRIDE)
            .returns(List::class.asClassName().parameterizedBy(String::class.asClassName()))
        if (items.isEmpty()) {
            builder.addStatement("return emptyList()")
        } else {
            builder.addCode("return listOf(\n")
            items.forEachIndexed { index, item ->
                val separator = if (index < items.size - 1) "," else ""
                builder.addCode("  %S$separator\n", item.qualifiedName)
            }
            builder.addCode(")\n")
        }
        return builder.build()
    }
}
