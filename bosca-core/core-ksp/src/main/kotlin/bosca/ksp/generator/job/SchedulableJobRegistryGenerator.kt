package bosca.ksp.generator.job

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundSchedulable
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.writeTo

class SchedulableJobRegistryGenerator(codeGenerator: CodeGenerator, val prefix: String) : AbstractGenerator<FoundSchedulable>(codeGenerator) {

    override fun generate(items: Collection<FoundSchedulable>) {
        if (items.isEmpty()) return

        val registrarType = TypeSpec
            .classBuilder("${prefix}SchedulableJobRegistrar")
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addSuperinterface(Types.SchedulableJobRegistrar)
            .addFunction(
                FunSpec
                    .builder("getSchedulableJobs")
                    .returns(List::class.asClassName().parameterizedBy(Types.SchedulableJob))
                    .addCode("return listOf(\n")
                    .apply {
                        items.forEach { item ->
                             addCode(
                                 "%T(%T::class, %S, %S),\n",
                                 Types.SchedulableJob,
                                 item.className,
                                 item.name,
                                 item.description
                             )
                        }
                    }
                    .addCode(")")
                    .addModifiers(KModifier.OVERRIDE)
                    .build()
            )
            .build()

        FileSpec.builder("bosca.scheduler", "${prefix}SchedulableJobRegistry")
            .addType(registrarType)
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
            .build()
            .writeTo(codeGenerator, true, items.mapNotNull { it.classDeclaration.containingFile })
    }
}
