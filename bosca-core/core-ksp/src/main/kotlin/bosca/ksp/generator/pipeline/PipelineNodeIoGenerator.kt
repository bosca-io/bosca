package bosca.ksp.generator.pipeline

import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundPipelineNode
import bosca.ksp.visitors.FoundPipelineOutput
import bosca.ksp.visitors.FoundPipelineSlot
import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Emits one `<Node>Serializer` object per `@PipelineNodeType` that declares input or output slots —
 * the node's typed I/O codec, derived from the same slot metadata the palette registrar
 * ([PipelineNodeSerializersGenerator]) projects to the editor:
 *
 *  - `deserialize(context, inputs)` decodes the inbound edge values into a generated `Inputs` data
 *    class, each slot already at its declared physical type (`kind`/`type` mapped exactly as
 *    `SlotValidator` enforces them): a typed `OBJECT` slot becomes that class, a typed `ARRAY`
 *    becomes `List<T>`, primitives become `String`/`Long`/`Double`/`Boolean`, `UUID` becomes the
 *    platform UUID, an untyped `OBJECT`/`ARRAY` stays `JsonObject`/`JsonArray`, and an `ANY` slot
 *    passes the raw `PipelineValue` through. A non-`required` slot is nullable; a required slot with
 *    no decodable value fails with a per-port message (a second line of defense — the executor's
 *    `SlotValidator` has already vetted the slots before `execute` runs).
 *  - `serialize(value)` (or `serialize<Port>(value)` per port when the node declares several) wraps
 *    a result into the [Types.PipelineValue] the node emits, pairing it with the slot's explicit
 *    serializer — and routes it via `onPort` when the node has named handles.
 *
 * Mirroring `SlotValidator`, a single-input node reads `inputs.first` (the sole inbound value,
 * whatever port the edge targeted); a multi-input node reads each slot by port name. All decoding
 * goes through `PipelineValue.decode` — explicit serializers only, so the generated code is
 * GraalVM-native safe like everything else KSP emits.
 */
class PipelineNodeIoGenerator(codeGenerator: CodeGenerator) : AbstractGenerator<FoundPipelineNode>(codeGenerator) {

    private val listSerializerFun = MemberName("kotlinx.serialization.builtins", "ListSerializer")
    private val builtinSerializerFun = MemberName("kotlinx.serialization.builtins", "serializer", isExtension = true)

    override fun generate(items: Collection<FoundPipelineNode>) {
        items.forEach { item ->
            val node = item.node as? ClassName ?: return@forEach
            if (item.inputs.isEmpty() && item.outputs.isEmpty()) return@forEach
            generate(item, node)
        }
    }

    private fun generate(item: FoundPipelineNode, node: ClassName) {
        val objectName = "${node.simpleName}Serializer"
        val label = item.label.ifBlank { node.simpleName }
        val inputsClass = ClassName(node.packageName, objectName, "Inputs")

        val builder = TypeSpec.objectBuilder(objectName)
            .addKdoc(
                "Typed input/output codec for [%T], generated from its `@PipelineNodeType` slots: " +
                    "`deserialize` decodes the inbound edge values to their declared physical types " +
                    "(`deserializePartial` tolerates missing slots, for dry-run tracing), and " +
                    "`serialize` wraps a result into the emitted `PipelineValue`.",
                node,
            )
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
        if (item.inputs.isNotEmpty()) {
            builder.addType(inputsType(item.inputs, node))
            builder.addFunction(deserializeFunction(item.inputs, label, inputsClass))
            builder.addType(partialInputsType(item.inputs, node))
            builder.addFunction(deserializePartialFunction(item.inputs, ClassName(node.packageName, objectName, "PartialInputs")))
        }
        serializeFunctions(item.outputs).forEach(builder::addFunction)

        FileSpec.builder(node.packageName, objectName)
            .addType(builder.build())
            .build()
            .writeTo(codeGenerator, false, listOfNotNull(item.classDeclaration.containingFile))
    }

    /** The `Inputs` data class — one property per declared slot, nullable when not `required`. */
    private fun inputsType(slots: List<FoundPipelineSlot>, node: ClassName): TypeSpec {
        val constructor = FunSpec.constructorBuilder()
        val properties = slots.map { slot ->
            val type = propertyType(slot.kind, slot.type).copy(nullable = !slot.required)
            constructor.addParameter(ParameterSpec.builder(slot.name, type).build())
            PropertySpec.builder(slot.name, type).initializer("%N", slot.name).build()
        }
        return TypeSpec.classBuilder("Inputs")
            .addKdoc("[%T]'s declared input slots, decoded to their physical types.", node)
            // Annotated directly (not just via the enclosing object) so Kover's annotatedBy filter
            // excludes the nested class from gated modules' coverage.
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addModifiers(KModifier.DATA)
            .primaryConstructor(constructor.build())
            .addProperties(properties)
            .build()
    }

    private fun deserializeFunction(slots: List<FoundPipelineSlot>, label: String, inputsClass: ClassName): FunSpec {
        val single = slots.size == 1
        val assignments = slots.map { slot ->
            CodeBlock.of("%N = %L", slot.name, slotExpression(slot, single, label))
        }
        return FunSpec.builder("deserialize")
            .addKdoc(
                "Decodes the inbound edge values into [Inputs]; fails with a per-port message when a " +
                    "required slot has no decodable value.",
            )
            .addParameter("context", Types.PipelineContext)
            .addParameter("inputs", Types.NodeInputs)
            .returns(inputsClass)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(\n", inputsClass)
                    .indent()
                    .add("%L,\n", assignments.joinToCode(",\n"))
                    .unindent()
                    .add(")")
                    .build()
            )
            .build()
    }

    /** The decode expression for one slot, mirroring `SlotValidator`'s single-vs-named port matching. */
    private fun slotExpression(slot: FoundPipelineSlot, single: Boolean, label: String): CodeBlock {
        val decoded = lenientSlotExpression(slot, single)
        if (!slot.required) return decoded
        return CodeBlock.of("%L ?: error(%S)", decoded, "$label: required input '${slot.name}' is missing")
    }

    /** [slotExpression] without the required-slot enforcement — a missing/null slot is just null. */
    private fun lenientSlotExpression(slot: FoundPipelineSlot, single: Boolean): CodeBlock {
        val accessor = if (single) CodeBlock.of("inputs.first") else CodeBlock.of("inputs[%S]", slot.name)
        if (slot.kind == "ANY") return accessor
        return CodeBlock.of("%L?.decode(%L, context.json)", accessor, serializerExpression(slot.kind, slot.type))
    }

    /** The `PartialInputs` data class — same slots as `Inputs`, but every property nullable. */
    private fun partialInputsType(slots: List<FoundPipelineSlot>, node: ClassName): TypeSpec {
        val constructor = FunSpec.constructorBuilder()
        val properties = slots.map { slot ->
            val type = propertyType(slot.kind, slot.type).copy(nullable = true)
            constructor.addParameter(ParameterSpec.builder(slot.name, type).build())
            PropertySpec.builder(slot.name, type).initializer("%N", slot.name).build()
        }
        return TypeSpec.classBuilder("PartialInputs")
            .addKdoc(
                "[%T]'s input slots decoded leniently — every property nullable, missing slots are " +
                    "null instead of failing. For dry-run paths that trace whatever is wired so far.",
                node,
            )
            // Annotated directly (not just via the enclosing object) so Kover's annotatedBy filter
            // excludes the nested class from gated modules' coverage.
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addModifiers(KModifier.DATA)
            .primaryConstructor(constructor.build())
            .addProperties(properties)
            .build()
    }

    private fun deserializePartialFunction(slots: List<FoundPipelineSlot>, partialClass: ClassName): FunSpec {
        val single = slots.size == 1
        val assignments = slots.map { slot ->
            CodeBlock.of("%N = %L", slot.name, lenientSlotExpression(slot, single))
        }
        return FunSpec.builder("deserializePartial")
            .addKdoc(
                "Decodes whatever is wired into [PartialInputs], tolerating missing required slots — " +
                    "for dry runs. A present-but-malformed value still fails, exactly as the run would.",
            )
            .addParameter("context", Types.PipelineContext)
            .addParameter("inputs", Types.NodeInputs)
            .returns(partialClass)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(\n", partialClass)
                    .indent()
                    .add("%L,\n", assignments.joinToCode(",\n"))
                    .unindent()
                    .add(")")
                    .build()
            )
            .build()
    }

    /**
     * One `serialize` function per output port. A single slot is the node's anonymous handle, so the
     * value is emitted portless (the executor flows it along every outbound edge); with several
     * declared ports each function is `serialize<Port>` and routes via `onPort`.
     */
    private fun serializeFunctions(outputs: List<FoundPipelineOutput>): List<FunSpec> {
        val single = outputs.size == 1
        return outputs.map { out ->
            val name = if (single) "serialize" else "serialize${pascal(out.name)}"
            val value = wrapExpression(out)
            val body =
                if (single) CodeBlock.of("return %L", value)
                else CodeBlock.of("return %L.onPort(%S)", value, out.name)
            FunSpec.builder(name)
                .addKdoc("Wraps the value emitted on the %L`%L` port, paired with its explicit serializer.", if (out.error) "error " else "", out.name)
                .addParameter(ParameterSpec.builder(out.name, propertyType(out.kind, out.type)).build())
                .returns(Types.PipelineValue)
                .addCode(body)
                .build()
        }
    }

    /** `PipelineValue.of/ofJson(...)` (or the raw pass-through for an `ANY` port) for one output. */
    private fun wrapExpression(out: FoundPipelineOutput): CodeBlock = when (out.kind) {
        "ANY" -> CodeBlock.of("%N", out.name)
        "OBJECT", "ARRAY" ->
            if (out.type == null) CodeBlock.of("%T.ofJson(%N)", Types.PipelineValue, out.name)
            else CodeBlock.of("%T.of(%N, %L)", Types.PipelineValue, out.name, serializerExpression(out.kind, out.type))
        else -> CodeBlock.of("%T.of(%N, %L)", Types.PipelineValue, out.name, serializerExpression(out.kind, out.type))
    }

    /** The slot's physical Kotlin type — the same kind mapping `SlotValidator` enforces at run time. */
    private fun propertyType(kind: String, type: ClassName?): TypeName = when (kind) {
        "STRING" -> STRING
        "INTEGER" -> LONG
        "NUMBER" -> DOUBLE
        "BOOLEAN" -> BOOLEAN
        "UUID" -> Types.UUID
        "OBJECT" -> type ?: Types.JsonObject
        "ARRAY" -> type?.let { LIST.parameterizedBy(it) } ?: Types.JsonArray
        else -> Types.PipelineValue
    }

    /** The explicit serializer expression matching [propertyType] — never a reflective lookup. */
    private fun serializerExpression(kind: String, type: ClassName?): CodeBlock = when (kind) {
        "STRING" -> CodeBlock.of("%T.%M()", STRING, builtinSerializerFun)
        "INTEGER" -> CodeBlock.of("%T.%M()", LONG, builtinSerializerFun)
        "NUMBER" -> CodeBlock.of("%T.%M()", DOUBLE, builtinSerializerFun)
        "BOOLEAN" -> CodeBlock.of("%T.%M()", BOOLEAN, builtinSerializerFun)
        "UUID" -> CodeBlock.of("%T()", Types.UUIDSerializer)
        "OBJECT" ->
            if (type == null) CodeBlock.of("%T.serializer()", Types.JsonObject)
            else CodeBlock.of("%T.serializer()", type)
        else ->
            if (type == null) CodeBlock.of("%T.serializer()", Types.JsonArray)
            else CodeBlock.of("%M(%T.serializer())", listSerializerFun, type)
    }

    /** `out` → `Out`, `on-error` → `OnError` — a port name as a function-name suffix. */
    private fun pascal(port: String): String = port
        .split('-', '_', '.', ' ')
        .filter { it.isNotBlank() }
        .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}
