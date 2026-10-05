package bosca.ksp.generator.pipeline

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundPipelineNode
import bosca.ksp.visitors.FoundPipelineOption
import bosca.ksp.visitors.FoundPipelineOutput
import bosca.ksp.visitors.FoundPipelineSetting
import bosca.ksp.visitors.FoundPipelineSlot
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.writeTo
import kotlin.reflect.KClass

/**
 * Emits one `${prefix}PipelineNodeSerializersProvider` per module that declares any
 * `@PipelineNodeType`. The generated class implements both
 * [ObjectProvider][bosca.di.ObjectProvider]`<PipelineNodeSerializers>` and `PipelineNodeSerializers`,
 * is `@Provider(name = "<prefix>")` so the DI KSP registers it, and exposes a `SerializersModule`
 * built from **explicit** `subclass(X::class, X.serializer())` entries inside
 * `polymorphic(PipelineNode::class) { … }`.
 *
 * The pipelines engine aggregates every module's provider via
 * `ProviderRegistry.findAll(PipelineNodeSerializers::class)` and folds the modules onto the
 * platform's global `Json` — so the open polymorphic node graph (de)serializes under GraalVM native
 * with no reflection. Mirrors `EventCatalogRegistryGenerator`.
 */
class PipelineNodeSerializersGenerator(
    codeGenerator: CodeGenerator,
    private val prefix: String,
) : AbstractGenerator<FoundPipelineNode>(codeGenerator) {

    private val serializersModuleFun = MemberName("kotlinx.serialization.modules", "SerializersModule")
    private val polymorphicFun = MemberName("kotlinx.serialization.modules", "polymorphic")
    private val subclassFun = MemberName("kotlinx.serialization.modules", "subclass")

    override fun generate(items: Collection<FoundPipelineNode>) {
        if (items.isEmpty()) return

        // Palette metadata, keyed by each node's @SerialName discriminator. `category` and each slot
        // `kind` are enum entry names carried verbatim from the annotation — emitted as
        // `NodeCategory.X` / `SlotKind.X`, so an invalid value is impossible at the annotation site
        // (no string mapping, no build-time guard needed). A node's output(s) are declared on `outputs`;
        // a single implicit output is `outputs = []` (the editor draws it as the anonymous handle).
        val descriptorEntries = items.map { item ->
            val label = item.label.ifBlank { item.classDeclaration.simpleName.asString() }
            // A blank group/subgroup stays null on the descriptor so consumers fall back to
            // bucketing by category — unannotated nodes render exactly as before.
            val groupArg = if (item.group.isBlank()) CodeBlock.of("null") else CodeBlock.of("%S", item.group)
            val subgroupArg = if (item.subgroup.isBlank()) CodeBlock.of("null") else CodeBlock.of("%S", item.subgroup)
            CodeBlock.of(
                "%T(key = %S, label = %S, category = %T.%L, group = %L, subgroup = %L, inputs = %L, outputs = %L, description = %S, settings = %L)",
                Types.NodeDescriptor, item.serialName, label, Types.NodeCategory, item.category,
                groupArg, subgroupArg,
                inputsInitializer(item.inputs), outputsInitializer(item.outputs), item.description,
                settingsInitializer(item.settings),
            )
        }
        val descriptorsInitializer = CodeBlock.of("listOf(\n%L,\n)", descriptorEntries.joinToCode(",\n"))

        val moduleInitializer = CodeBlock.builder()
            .add("%M {\n", serializersModuleFun)
            .indent()
            .add("%M(%T::class) {\n", polymorphicFun, Types.PipelineNode)
            .indent()
            .apply {
                items.forEach { item ->
                    add("%M(%T::class, %T.serializer())\n", subclassFun, item.node, item.node)
                }
            }
            .unindent()
            .add("}\n")
            .unindent()
            .add("}")
            .build()

        val providerClass = TypeSpec
            .classBuilder("${prefix}PipelineNodeSerializersProvider")
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addAnnotation(
                AnnotationSpec.builder(Provider::class)
                    .addMember("singleton = true")
                    .addMember("name = %S", prefix)
                    .build()
            )
            .addSuperinterface(Types.ObjectProvider.parameterizedBy(Types.PipelineNodeSerializers))
            .addSuperinterface(Types.PipelineNodeSerializers)
            .addProperty(
                PropertySpec.builder(
                    "type",
                    KClass::class.asClassName().parameterizedBy(Types.PipelineNodeSerializers),
                    KModifier.OVERRIDE,
                ).initializer("%T::class", Types.PipelineNodeSerializers).build()
            )
            .addProperty(
                PropertySpec.builder("module", Types.SerializersModule, KModifier.OVERRIDE)
                    .initializer(moduleInitializer)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "descriptors",
                    List::class.asClassName().parameterizedBy(Types.NodeDescriptor),
                    KModifier.OVERRIDE,
                )
                    .initializer(descriptorsInitializer)
                    .build()
            )
            .addFunction(
                FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(Types.PipelineNodeSerializers)
                    .addCode("return this")
                    .build()
            )
            .build()

        FileSpec.builder("bosca.pipelines.node", "${prefix}PipelineNodeSerializers")
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("\"OPT_IN_USAGE\"")
                    .build()
            )
            .addType(providerClass)
            .build()
            .writeTo(codeGenerator, true, items.mapNotNull { it.classDeclaration.containingFile })
    }

    /** `listOf(NodeInputSlot(...), ...)` for the node's declared slots, or `emptyList()`. */
    private fun inputsInitializer(slots: List<FoundPipelineSlot>): CodeBlock =
        if (slots.isEmpty()) CodeBlock.of("emptyList()")
        else CodeBlock.of("listOf(%L)", slots.map(::slotBlock).joinToCode(", "))

    /**
     * One `NodeInputSlot(...)`. A blank `typeLabel` falls back to the kind name; a blank `type`/
     * `schema` becomes `null`; a non-blank `schema` is parsed once at provider init via the default
     * [Json] (the slot schema is plain JSON — no module-bound polymorphic types — so the default is
     * correct and native-safe).
     */
    private fun slotBlock(slot: FoundPipelineSlot): CodeBlock {
        val typeLabel = slot.typeLabel.ifBlank { slot.kind }
        val descArg = if (slot.description.isBlank()) CodeBlock.of("null") else CodeBlock.of("%S", slot.description)
        val typeArg = slot.type
            ?.let { CodeBlock.of("%T.serializer().descriptor.serialName", it) } ?: CodeBlock.of("null")
        // The type's field structure, derived from the same explicit serializer descriptor as `type` (so
        // native-safe) — powers the editor's hover introspection; `null` when the slot isn't a typed object.
        val structureArg = slot.type
            ?.let { CodeBlock.of("%T.of(%T.serializer().descriptor)", Types.NodeFieldDescriptor, it) } ?: CodeBlock.of("null")
        val schemaArg =
            if (slot.schema.isBlank()) CodeBlock.of("null")
            else CodeBlock.of("%T.parseToJsonElement(%S)", Types.Json, slot.schema)
        return CodeBlock.of(
            "%T(name = %S, typeLabel = %S, description = %L, kind = %T.%L, type = %L, schema = %L, required = %L, structure = %L)",
            Types.NodeInputSlot, slot.name, typeLabel, descArg, Types.SlotKind, slot.kind, typeArg, schemaArg, slot.required, structureArg,
        )
    }

    /** `listOf(NodeOutputSlot(...), ...)` for the node's declared output ports, or `emptyList()`. */
    private fun outputsInitializer(outputs: List<FoundPipelineOutput>): CodeBlock =
        if (outputs.isEmpty()) CodeBlock.of("emptyList()")
        else CodeBlock.of("listOf(%L)", outputs.map(::outputBlock).joinToCode(", "))

    private fun outputBlock(out: FoundPipelineOutput): CodeBlock {
        val typeArg = out.type
            ?.let { CodeBlock.of("%T.serializer().descriptor.serialName", it) } ?: CodeBlock.of("null")
        // The emitted type's field structure, for the editor's hover introspection — same explicit-serializer
        // derivation as `type`, so native-safe; `null` when the port isn't a typed object.
        val structureArg = out.type
            ?.let { CodeBlock.of("%T.of(%T.serializer().descriptor)", Types.NodeFieldDescriptor, it) } ?: CodeBlock.of("null")
        // typeLabel/description are display-only and supplement the port name, so a blank stays null
        // (the editor falls back to the port name) — unlike an input slot's typeLabel, which kinds-up.
        val typeLabelArg = if (out.typeLabel.isBlank()) CodeBlock.of("null") else CodeBlock.of("%S", out.typeLabel)
        val descriptionArg = if (out.description.isBlank()) CodeBlock.of("null") else CodeBlock.of("%S", out.description)
        return CodeBlock.of(
            "%T(name = %S, kind = %T.%L, error = %L, type = %L, typeLabel = %L, description = %L, structure = %L)",
            Types.NodeOutputSlot, out.name, Types.SlotKind, out.kind, out.error, typeArg, typeLabelArg, descriptionArg, structureArg,
        )
    }

    /** `listOf(NodeSettingSlot(...), ...)` for the node's declared settings, or `emptyList()`. */
    private fun settingsInitializer(settings: List<FoundPipelineSetting>): CodeBlock =
        if (settings.isEmpty()) CodeBlock.of("emptyList()")
        else CodeBlock.of("listOf(%L)", settings.map(::settingBlock).joinToCode(", "))

    /**
     * One `NodeSettingSlot(...)`. `control` is the annotation enum entry name, emitted as
     * `SettingControl.X`; `reference` is `ReferenceSource.X` or `null` (the `NONE` sentinel). Every
     * blank string modifier becomes `null`, and `options`/`fields` recurse — all compile-time literals
     * and enum constants, so this is reflection-free and native-safe (simpler than `slotBlock`, which
     * parses a JSON schema).
     */
    private fun settingBlock(s: FoundPipelineSetting): CodeBlock {
        fun strArg(v: String): CodeBlock = if (v.isBlank()) CodeBlock.of("null") else CodeBlock.of("%S", v)
        val referenceArg = s.reference?.let { CodeBlock.of("%T.%L", Types.ReferenceSource, it) } ?: CodeBlock.of("null")
        val optionsArg =
            if (s.options.isEmpty()) CodeBlock.of("emptyList()")
            else CodeBlock.of("listOf(%L)", s.options.map(::optionBlock).joinToCode(", "))
        return CodeBlock.of(
            "%T(name = %S, control = %T.%L, label = %L, description = %L, placeholder = %L, default = %L, " +
                "required = %L, secret = %L, mono = %L, language = %L, reference = %L, options = %L, " +
                "fields = %L, itemLabel = %L, group = %L, visibleWhenSetting = %L, visibleWhenEquals = %L)",
            Types.NodeSettingSlot, s.name, Types.SettingControl, s.control,
            strArg(s.label), strArg(s.description), strArg(s.placeholder), strArg(s.default),
            s.required, s.secret, s.mono, strArg(s.language), referenceArg, optionsArg,
            settingsInitializer(s.fields), strArg(s.itemLabel), strArg(s.group),
            strArg(s.visibleWhenSetting), strArg(s.visibleWhenEquals),
        )
    }

    private fun optionBlock(o: FoundPipelineOption): CodeBlock =
        CodeBlock.of(
            "%T(value = %S, label = %L)",
            Types.NodeSettingOption, o.value,
            if (o.label.isBlank()) CodeBlock.of("null") else CodeBlock.of("%S", o.label),
        )
}
