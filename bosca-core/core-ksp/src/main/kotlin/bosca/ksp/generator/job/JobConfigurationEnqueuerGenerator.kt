package bosca.ksp.generator.job

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundJobDefinition
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class JobConfigurationEnqueuerGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundJobDefinition>(codeGenerator) {

    override fun generate(items: Collection<FoundJobDefinition>) {
        items.forEach { item ->
            val definition = item.definition
            val queueName = item.queueName
            val jobConfigurationEnqueuer = item.JobConfigurationEnqueuer
            val jobName = item.jobName
            val displayName = item.displayName
            val classDefinition = item.classDeclaration
            FileSpec
                .builder(jobConfigurationEnqueuer)
                .addType(
                    TypeSpec
                        .classBuilder(ClassName(jobConfigurationEnqueuer.packageName, "${jobConfigurationEnqueuer.simpleName}Provider"))
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(AnnotationSpec.builder(Providers::class).build())
                        .addFunction(
                            FunSpec
                                .builder("provideEnqueuer")
                                .addAnnotation(AnnotationSpec.builder(Provider::class).addMember("singleton = true, name = %S", jobName).build())
                                .addCode("return %T()", jobConfigurationEnqueuer)
                                .returns(Types.JobConfigurationEnqueuer)
                                .build()
                        )
                        .build()
                )
                .addType(
                    TypeSpec.classBuilder(jobConfigurationEnqueuer)
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addSuperinterface(Types.JobConfigurationEnqueuer)
                        .addProperty(
                            PropertySpec.builder("queueName", String::class, KModifier.OVERRIDE).initializer("%S", queueName).build()
                        )
                        .addProperty(
                            PropertySpec.builder("displayName", String::class, KModifier.OVERRIDE).initializer("%S", displayName).build()
                        )
                        .addFunction(
                            FunSpec
                                .builder("prepare")
                                .addAnnotation(
                                    AnnotationSpec
                                        .builder(ClassName("kotlin", "OptIn"))
                                        .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                        .build()
                                )
                                .addParameter("configuration", Types.JsonElement)
                                .addParameter(ParameterSpec.builder(
                                    name = "initializer",
                                    type = LambdaTypeName.get(receiver = Types.Job, returnType = ClassName("kotlin", "Unit")).copy(suspending = true),
                                ).build())
                                .returns(Types.Job)
                                .addCode("val json = provide<%T>()\n", Types.Json)
                                .addCode("val definition = json.decodeFromJsonElement<%T>(configuration)\n", ClassName(definition.packageName.asString(), definition.simpleName.asString()))
                                .addCode("return definition.prepare(initializer = initializer)\n", Types.Json)
                                .addModifiers(KModifier.SUSPEND, KModifier.OVERRIDE)
                                .build(),
                        )
                        .addFunction(
                            FunSpec
                                .builder("enqueue")
                                .addAnnotation(
                                    AnnotationSpec
                                        .builder(ClassName("kotlin", "OptIn"))
                                        .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                        .build()
                                )
                                .addParameter("configuration", Types.JsonElement)
                                .addParameter(ParameterSpec.builder(
                                    name = "initializer",
                                    type = LambdaTypeName.get(receiver = Types.Job, returnType = ClassName("kotlin", "Unit")).copy(suspending = true),
                                ).build())
                                .returns(Types.Job)
                                .addCode("val json = provide<%T>()\n", Types.Json)
                                .addCode("val definition = json.decodeFromJsonElement<%T>(configuration)\n", ClassName(definition.packageName.asString(), definition.simpleName.asString()))
                                .addCode("return definition.enqueue(initializer = initializer)\n", Types.Json)
                                .addModifiers(KModifier.SUSPEND, KModifier.OVERRIDE)
                                .build(),
                        )
                        .addFunction(
                            FunSpec
                                .builder("enqueueLater")
                                .addAnnotation(
                                    AnnotationSpec
                                        .builder(ClassName("kotlin", "OptIn"))
                                        .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                        .build()
                                )
                                .addParameter("configuration", Types.JsonElement)
                                .addParameter("timeout", ClassName("kotlin.time", "Duration"))
                                .addParameter(ParameterSpec.builder(
                                    name = "initializer",
                                    type = LambdaTypeName.get(receiver = Types.Job, returnType = ClassName("kotlin", "Unit")).copy(suspending = true),
                                ).build())
                                .returns(Types.Job)
                                .addCode("val json = provide<%T>()\n", Types.Json)
                                .addCode("val definition = json.decodeFromJsonElement<%T>(configuration)\n", ClassName(definition.packageName.asString(), definition.simpleName.asString()))
                                .addCode("return definition.enqueueLater(timeout = timeout, initializer = initializer)\n", Types.Json)
                                .addModifiers(KModifier.SUSPEND, KModifier.OVERRIDE)
                                .build(),
                        )
                        .addFunction(
                            FunSpec
                                .builder("queue")
                                .returns(Types.JobQueue)
                                .addCode("return provide<%T>(name = %S)", Types.JobQueue, queueName)
                                .addModifiers(KModifier.SUSPEND, KModifier.OVERRIDE)
                                .build()
                        )
                        .build()
                )
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addImport("bosca.di", "provide")
                .addImport("kotlinx.serialization.json", "decodeFromJsonElement")
                .build()
                .writeTo(codeGenerator, true, originatingKSFiles = listOf(classDefinition.containingFile ?: error("No containing file")))
        }
    }
}