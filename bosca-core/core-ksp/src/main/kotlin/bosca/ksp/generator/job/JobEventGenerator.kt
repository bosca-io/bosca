package bosca.ksp.generator.job

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundJobEvent
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.writeTo

class JobEventGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundJobEvent>(codeGenerator) {

    override fun generate(items: Collection<FoundJobEvent>) {
        items.forEach { (event, jobs, pubsubChannel, _, _, classDefinition) ->
            val names = classDefinition.primaryConstructor?.parameters?.mapTo(mutableSetOf()) {
                it.name?.asString() ?: error("missing parameter name")
            } ?: emptySet()
            val jobBlocks = jobs.joinToCode(separator = "\n") {
                val params = mutableListOf<Any>(ClassName(it.packageName.asString(), it.simpleName.asString()))
                val parameterNames = it.primaryConstructor?.parameters
                    ?.asSequence()
                    ?.filter { names.contains(it.name?.asString() ?: error("missing parameter name")) }
                    ?.flatMap {
                        val name = it.name?.asString() ?: error("missing parameter name")
                        listOf(name, name)
                    }
                    ?.toList() ?: emptyList()
                params.addAll(parameterNames)
                CodeBlock.of(buildString {
                    append("run {\n")
                    append("\tval definition = %T(\n")
                    repeat(parameterNames.size / 2) {
                        append("\t\t%L = %L, \n")
                    }
                    append("\t)\n")
                    append("\tdefinition.enqueue()\n")
                    append("}\n")
                }, *params.toTypedArray())
            }
            val eventFqdn = "${classDefinition.packageName.asString()}.${classDefinition.simpleName.asString()}"
            val generatedAnnotation = AnnotationSpec.builder(Generated::class).build()
            val internalDispatch = FunSpec
                .builder("internalDispatch")
                .addAnnotation(generatedAnnotation)
                .receiver(event)
                .addModifiers(KModifier.SUSPEND, KModifier.INTERNAL)
                .addCode(jobBlocks)
            if (pubsubChannel.isNotBlank()) {
                internalDispatch.addCode(
                    """
                    run {
                        val pubsub = provide<%T>()
                        pubsub.publish(%S, %T.serializer(), this)
                    }
                    """.trimIndent(), Types.PubSubService, pubsubChannel, event
                )
            }
            internalDispatch.addCode(
                """

                run {
                    val pipelineDispatcherProvider = provideProvider<%T>()
                    if (pipelineDispatcherProvider.exists) {
                        pipelineDispatcherProvider.get().dispatch(%S, this, %T.serializer())
                    }
                }
                """.trimIndent(), Types.PipelineEventDispatcher, eventFqdn, event
            )
            val dispatch = FunSpec
                .builder("dispatch")
                .addAnnotation(generatedAnnotation)
                .receiver(event)
                .addModifiers(KModifier.SUSPEND)
                .addCode("val manager = eventManager()\n")
                .addCode("if (!manager.isEnabled(this)) {\n")
                .addCode("\tmanager.deferDispatch(this) { internalDispatch() }\n")
                .addCode("\treturn\n")
                .addCode("}\n")
                .addCode("internalDispatch()\n")
            FileSpec
                .builder(ClassName(classDefinition.packageName.asString(), "${classDefinition.simpleName.asString()}Ext"))
                .addFunction(internalDispatch.build())
                .addFunction(dispatch.build())
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addImport("bosca.di", "provide")
                .addImport("bosca.di", "provideProvider")
                .addImport("bosca.events", "eventManager")
                .also { builder ->
                    jobs.forEach { builder.addImport(it.packageName.asString(), "enqueue") }
                }
                .build()
                .writeTo(codeGenerator, true, originatingKSFiles = listOf(classDefinition.containingFile ?: error("No containing file")))
        }
    }
}
