package bosca.ksp.generator.graphql

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundSchema
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

class SchemaGenerator(codeGenerator: CodeGenerator, private val prefix: String, private val logger: KSPLogger) : AbstractGenerator<FoundSchema>(codeGenerator) {

    override fun generate(items: Collection<FoundSchema>) {
        items.forEach { (classDeclaration, schema, resources) ->
            val classBuilder = TypeSpec
                .classBuilder(ClassName(schema.packageName, "$prefix${schema.simpleName}"))
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addSuperinterface(Types.SchemaRegistrar)
                .addSuperinterface(classDeclaration.asType(emptyList()).toTypeName())
                .addOriginatingKSFile(classDeclaration.containingFile ?: error("No containing file"))
                .addProperty(
                    PropertySpec.builder("resources", Array::class.asClassName().parameterizedBy(String::class.asClassName()))
                        .initializer("arrayOf(${resources.joinToString { "%S" }})", *resources.map { it.resource }.toTypedArray())
                        .addModifiers(KModifier.PRIVATE)
                        .build()
                )
                .addProperties(
                    resources.map {
                        PropertySpec.builder(it.name, String::class)
                            .addModifiers(KModifier.OVERRIDE)
                            .initializer("%S", it.resource)
                            .build()
                    }
                )
                .addFunction(
                    FunSpec.builder("load")
                        .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                        .addCode("return resources.joinToString {\n")
                        .addCode("  javaClass.classLoader.getResourceAsStream(\"graphql/\$it\")?.use { it.readAllBytes()?.decodeToString() } ?: error(\"Schema not found: \$it\")\n")
                        .addCode("}\n")
                        .returns(String::class)
                        .build()

                )
            FileSpec.builder(schema)
                .addType(classBuilder.build())
                .build()
                .writeTo(codeGenerator, true)
        }
    }
}