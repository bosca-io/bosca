package bosca.ksp.generator.job

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundJobDefinition
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ksp.writeTo

class JobEnqueueGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundJobDefinition>(codeGenerator) {

    override fun generate(items: Collection<FoundJobDefinition>) {
        items.forEach { item ->
            val job = item.job
            val executor = item.executor
            val definition = item.definition
            val queueName = item.queueName
            val displayName = item.displayName
            val definitionClassName = ClassName(definition.packageName.asString(), definition.simpleName.asString())
            FileSpec.builder(executor)
                .addFunction(
                    FunSpec
                        .builder("prepare")
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addCode("return prepare(%T::class, displayName = %S)", job, displayName)
                        .returns(Types.Job)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addFunction(
                    FunSpec
                        .builder("prepare")
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addParameter(ParameterSpec.builder(
                            name = "initializer",
                            type = LambdaTypeName.get(receiver = Types.Job, returnType = ClassName("kotlin", "Unit")).copy(suspending = true),
                        ).build())
                        .addCode("return prepare(%T::class, displayName = %S, initializer = initializer)", job, displayName)
                        .returns(Types.Job)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addFunction(
                    FunSpec
                        .builder("enqueue")
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addCode("val jobQueue = provide<%T>(name = %S)\n", Types.JobQueue, queueName)
                        .addCode("return enqueue(jobQueue, %T::class, displayName = %S)", job, displayName)
                        .returns(Types.Job)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addFunction(
                    FunSpec
                        .builder("enqueue")
                        .addParameter("context", Types.PipelineContext)
                        .addParameter("nodeId", ClassName("kotlin", "String"))
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addCode("val jobQueue = provide<%T>(name = %S)\n", Types.JobQueue, queueName)
                        .addCode("""
                            val child = prepare {
                                context.correlate(this, nodeId)
                            }
                            return context.enqueue(jobQueue, child)
                        """.trimIndent())
                        .returns(Types.UUID)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addFunction(
                    FunSpec
                        .builder("jobQueue")
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addCode("return provide<%T>(name = %S)\n", Types.JobQueue, queueName)
                        .returns(Types.JobQueue)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addFunction(
                    FunSpec
                        .builder("enqueue")
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addParameter(ParameterSpec.builder(
                            name = "initializer",
                            type = LambdaTypeName.get(receiver = Types.Job, returnType = ClassName("kotlin", "Unit")).copy(suspending = true),
                        ).build())
                        .addCode("val jobQueue = provide<%T>(name = %S)\n", Types.JobQueue, queueName)
                        .addCode("return enqueue(jobQueue, %T::class, displayName = %S, initializer = initializer)", job, displayName)
                        .returns(Types.Job)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addFunction(
                    FunSpec
                        .builder("enqueueLater")
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addParameter("timeout", ClassName("kotlin.time", "Duration"))
                        .addCode("val jobQueue = provide<%T>(name = %S)\n", Types.JobQueue, queueName)
                        .addCode("return enqueueLater(jobQueue, %T::class, displayName = %S, timeout = timeout)", job, displayName)
                        .returns(Types.Job)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addFunction(
                    FunSpec
                        .builder("enqueueLater")
                        .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                        .addAnnotation(
                            AnnotationSpec
                                .builder(ClassName("kotlin", "OptIn"))
                                .addMember("kotlin.uuid.ExperimentalUuidApi::class")
                                .build()
                        )
                        .addParameter(ParameterSpec.builder(
                            name = "initializer",
                            type = LambdaTypeName.get(receiver = Types.Job, returnType = ClassName("kotlin", "Unit")).copy(suspending = true),
                        ).defaultValue("{}").build())
                        .addParameter("timeout", ClassName("kotlin.time", "Duration"))
                        .addCode("val jobQueue = provide<%T>(name = %S)\n", Types.JobQueue, queueName)
                        .addCode("return enqueueLater(jobQueue, %T::class, displayName = %S, timeout = timeout, initializer = initializer)", job, displayName)
                        .returns(Types.Job)
                        .receiver(definitionClassName)
                        .addModifiers(KModifier.SUSPEND)
                        .build()
                )
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addImport("bosca.di", "provide")
                .addImport("bosca.sharedqueue.jobs", "enqueue")
                .addImport("bosca.sharedqueue.jobs", "enqueueLater")
                .addImport("bosca.sharedqueue.jobs", "prepare")
                .build()
                .writeTo(codeGenerator, true)
        }
    }
}