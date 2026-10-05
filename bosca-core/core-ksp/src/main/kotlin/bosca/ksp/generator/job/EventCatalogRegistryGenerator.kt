package bosca.ksp.generator.job

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundJobEvent
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.writeTo
import kotlin.reflect.KClass

/**
 * Emits one `${prefix}EventCatalogRegistrarProvider` per module that declares any
 * [@JobEvent][bosca.events.annotation.JobEvent]. The generated class implements both
 * [ObjectProvider][bosca.di.ObjectProvider]`<EventCatalogRegistrar>` and `EventCatalogRegistrar`
 * itself, and is annotated `@Provider(name = "<prefix>")` so the DI KSP processor registers it
 * as a named provider — making every module's events discoverable via
 * `ProviderRegistry.findAll(EventCatalogRegistrar::class)` (see `EventCatalogServiceImpl`).
 *
 * The `fqdn` of each descriptor is built from the event's package + simple name, matching the
 * key `JobEventGenerator` passes to `PipelineEventDispatcher.dispatch`, so catalog entries line up
 * with the accepted input types stored on triggered pipelines and automation rules. Filterable fields are derived
 * at runtime from the event's explicitly-compiled `serializer().descriptor`, which is
 * GraalVM-native safe (no reflective serializer lookup).
 */
class EventCatalogRegistryGenerator(
    codeGenerator: CodeGenerator,
    private val prefix: String,
) : AbstractGenerator<FoundJobEvent>(codeGenerator) {

    override fun generate(items: Collection<FoundJobEvent>) {
        if (items.isEmpty()) return

        val descriptors = items.map { item ->
            val declaration = item.classDeclaration
            val simpleName = declaration.simpleName.asString()
            val fqdn = "${declaration.packageName.asString()}.$simpleName"
            val displayName = item.displayName.ifBlank { simpleName }
            val pubsubChannel =
                if (item.pubsubChannel.isBlank()) CodeBlock.of("null")
                else CodeBlock.of("%S", item.pubsubChannel)
            val jobNames =
                if (item.jobs.isEmpty()) CodeBlock.of("emptyList()")
                else CodeBlock.of(
                    "listOf(%L)",
                    item.jobs.map { CodeBlock.of("%S", it.simpleName.asString()) }.joinToCode(", "),
                )
            CodeBlock.of(
                "%T(\n  fqdn = %S,\n  displayName = %S,\n  description = %S,\n  pubsubChannel = %L,\n  jobNames = %L,\n  fields = %T.of(%T.serializer().descriptor),\n)",
                Types.EventDescriptor,
                fqdn,
                displayName,
                item.description,
                pubsubChannel,
                jobNames,
                Types.EventCatalogFields,
                item.event,
            )
        }

        val eventsInitializer = CodeBlock.of("listOf(\n%L,\n)", descriptors.joinToCode(",\n"))

        // fqdn → explicitly compiled serializer, so consumers holding only the fqdn + JSON payload
        // can rebuild the typed event without reflective lookup (GraalVM-native safe).
        val serializerEntries = items.map { item ->
            val declaration = item.classDeclaration
            val fqdn = "${declaration.packageName.asString()}.${declaration.simpleName.asString()}"
            CodeBlock.of("%S to %T.serializer()", fqdn, item.event)
        }
        val serializersInitializer = CodeBlock.of("mapOf(\n%L,\n)", serializerEntries.joinToCode(",\n"))
        val kSerializerStar = ClassName("kotlinx.serialization", "KSerializer").parameterizedBy(STAR)

        val providerClass = TypeSpec
            .classBuilder("${prefix}EventCatalogRegistrarProvider")
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addAnnotation(
                AnnotationSpec.builder(Provider::class)
                    .addMember("singleton = true")
                    .addMember("name = %S", prefix)
                    .build()
            )
            .addSuperinterface(Types.ObjectProvider.parameterizedBy(Types.EventCatalogRegistrar))
            .addSuperinterface(Types.EventCatalogRegistrar)
            .addProperty(
                PropertySpec
                    .builder(
                        "type",
                        KClass::class.asClassName().parameterizedBy(Types.EventCatalogRegistrar),
                        KModifier.OVERRIDE,
                    )
                    .initializer("%T::class", Types.EventCatalogRegistrar)
                    .build()
            )
            .addProperty(
                PropertySpec
                    .builder(
                        "events",
                        List::class.asClassName().parameterizedBy(Types.EventDescriptor),
                        KModifier.OVERRIDE,
                    )
                    .initializer(eventsInitializer)
                    .build()
            )
            .addProperty(
                PropertySpec
                    .builder(
                        "serializers",
                        Map::class.asClassName().parameterizedBy(String::class.asClassName(), kSerializerStar),
                        KModifier.OVERRIDE,
                    )
                    .initializer(serializersInitializer)
                    .build()
            )
            .addFunction(
                FunSpec
                    .builder("get")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(Types.EventCatalogRegistrar)
                    .addCode("return this")
                    .build()
            )
            .build()

        FileSpec
            .builder("bosca.events.catalog", "${prefix}EventCatalogRegistry")
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"")
                    .build()
            )
            .addType(providerClass)
            .build()
            .writeTo(codeGenerator, true, items.mapNotNull { it.classDeclaration.containingFile })
    }
}
