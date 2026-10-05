package bosca.pipelines.node

import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SlotKind
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.json.JsonElement

/**
 * One field of a typed slot's object [structure][NodeInputSlot.structure] — its [name], a coarse,
 * UI-friendly [type] label, and, when the field's type (or a list's item type) is itself an object, its
 * own [fields] (recursively). Lets the pipeline editor *introspect* what a typed input/output object looks
 * like — the Type Browser expands nested objects — without the frontend knowing any domain model. Derived
 * once, at provider init, from the type's [SerialDescriptor] via [of] — reflection-free (the serializer is
 * always passed explicitly), exactly like the slot's [type][NodeInputSlot.type] serial name.
 */
class NodeFieldDescriptor(
    val name: String,
    val type: String,
    /** The object type's own fields when this field (or a list item) is an object; `null` for a leaf/cycle. */
    val fields: List<NodeFieldDescriptor>? = null,
) {
    companion object {
        /** Bounds the walk so a deep or (despite the cycle guard) pathological graph can't blow up the payload. */
        private const val MAX_DEPTH = 8

        /** The fields of [descriptor], recursively expanding object-typed (and list-of-object) fields. */
        fun of(descriptor: SerialDescriptor): List<NodeFieldDescriptor> =
            fieldsOf(descriptor, setOf(descriptor.serialName.removeSuffix("?")), 0)

        private fun fieldsOf(descriptor: SerialDescriptor, seen: Set<String>, depth: Int): List<NodeFieldDescriptor> =
            (0 until descriptor.elementsCount).map { index ->
                val element = descriptor.getElementDescriptor(index)
                NodeFieldDescriptor(
                    name = descriptor.getElementName(index),
                    type = typeLabel(element),
                    fields = nested(element, seen, depth),
                )
            }

        /**
         * The nested fields to expand under [element] — the fields of its object type (or of a list's item
         * type), or `null` when it's a leaf (primitive/enum/contextual/map), a cycle (the object type is
         * already on the path via [seen]), or past [MAX_DEPTH].
         */
        private fun nested(element: SerialDescriptor, seen: Set<String>, depth: Int): List<NodeFieldDescriptor>? {
            if (depth >= MAX_DEPTH) return null
            val obj = objectDescriptor(element) ?: return null
            val name = obj.serialName.removeSuffix("?")
            if (name in seen) return null
            return fieldsOf(obj, seen + name, depth + 1).ifEmpty { null }
        }

        /** The class descriptor to expand for [element]: itself if a class, a list's item if that's a class; else null. */
        private fun objectDescriptor(element: SerialDescriptor): SerialDescriptor? = when (element.kind) {
            StructureKind.CLASS -> element
            StructureKind.LIST -> element.getElementDescriptor(0).takeIf { it.kind == StructureKind.CLASS }
            else -> null
        }

        /** A coarse display label from an element's [SerialKind]; a list shows its item's label, complex kinds the short serial name. */
        @OptIn(ExperimentalSerializationApi::class)
        private fun typeLabel(element: SerialDescriptor): String {
            val base = when (element.kind) {
                PrimitiveKind.STRING -> "String"
                PrimitiveKind.BOOLEAN -> "Boolean"
                PrimitiveKind.BYTE -> "Byte"
                PrimitiveKind.SHORT -> "Short"
                PrimitiveKind.INT -> "Int"
                PrimitiveKind.LONG -> "Long"
                PrimitiveKind.FLOAT -> "Float"
                PrimitiveKind.DOUBLE -> "Double"
                PrimitiveKind.CHAR -> "Char"
                SerialKind.ENUM -> "Enum"
                // A @Contextual field (UUID, OffsetDateTime, …) has a placeholder serial name — use the
                // captured runtime type so it reads as "UUID", not "ContextualSerializer".
                SerialKind.CONTEXTUAL -> element.capturedKClass?.simpleName ?: "Object"
                StructureKind.LIST -> "List<${typeLabel(element.getElementDescriptor(0))}>"
                StructureKind.MAP -> "Map"
                else -> element.serialName.removeSuffix("?").substringAfterLast('.')
            }
            return if (element.isNullable) "$base?" else base
        }
    }
}

/**
 * One input port of a node type: its [name] (the edge target port it matches), a coarse
 * [typeLabel] for display, and the constraints the executor enforces against the inbound value —
 * [kind] (incl. [SlotKind.UUID]), an optional specific-object [type] (a serial name), and an
 * optional [schema] (the [JsonSchemaValidator] subset). A [required] slot with no inbound value
 * fails the run. [NodeCategory] and [SlotKind] live in `core-annotations` so the `@InputSlot`
 * annotation can carry them; this model reuses them verbatim.
 */
class NodeInputSlot(
    val name: String,
    val typeLabel: String,
    val kind: SlotKind = SlotKind.ANY,
    /** Short human description of what the slot expects, surfaced in the editor inspector; `null` = none. */
    val description: String? = null,
    val type: String? = null,
    val schema: JsonElement? = null,
    val required: Boolean = true,
    /** The [type]'s fields, for the editor's hover introspection; `null` when the slot isn't a specific object. */
    val structure: List<NodeFieldDescriptor>? = null,
)

/**
 * One named output port of a node type: a value emitted on it (via `PipelineValue.onPort([name])`)
 * routes along edges whose `sourcePort` matches. [error] marks a failure port — an emitted-but-
 * unwired error port fails the run rather than reporting success (see `@OutputSlot`). [kind] and the
 * optional specific-object [type] (a serial name) mirror [NodeInputSlot] on the producing side: the
 * connection validator resolves a wire's source kind/type from the matching port, so a typed object
 * slot can accept (or refuse) *this port* rather than the node's implicit output.
 */
class NodeOutputSlot(
    val name: String,
    val kind: SlotKind = SlotKind.ANY,
    val error: Boolean = false,
    val type: String? = null,
    /** Human display label for the emitted value, shown in the editor's "Produces" section; `null` = use the port name. */
    val typeLabel: String? = null,
    /** Short human description of what the port emits, shown in the editor; `null` = none. */
    val description: String? = null,
    /** The [type]'s fields, for the editor's hover introspection; `null` when the port isn't a specific object. */
    val structure: List<NodeFieldDescriptor>? = null,
)

/**
 * One choice of an [SettingControl.ENUM] setting, or a literal extra prepended to a
 * [SettingControl.REFERENCE] picker. [value] is stored in the node's settings; [label] is shown to the
 * builder (`null` = use [value]). Mirrors `@SettingOption` on the runtime side.
 */
class NodeSettingOption(
    val value: String,
    val label: String? = null,
)

/**
 * One editable setting of a node type — its [name] (the key in the node's graph JSON), the editor
 * [control] that edits it, and display/validation modifiers. The Studio inspector renders a node type's
 * settings generically from these, so a node's whole form is data-driven (no hardcoded per-kind form).
 * Mirrors `@SettingSlot`; built at compile time by the KSP node registrar, never runtime reflection.
 *
 * [default] is the node's own default as a string (drives the editor's omit-on-default + new-node seed
 * + placeholder); [reference] names the server list a [SettingControl.REFERENCE] picks from; [fields]
 * are the row sub-fields of a [SettingControl.GROUP_LIST]; [visibleWhenSetting]/[visibleWhenEquals]
 * gate visibility on another setting's effective value.
 */
class NodeSettingSlot(
    val name: String,
    val control: SettingControl,
    val label: String? = null,
    val description: String? = null,
    val placeholder: String? = null,
    val default: String? = null,
    val required: Boolean = false,
    val secret: Boolean = false,
    val mono: Boolean = false,
    val language: String? = null,
    val reference: ReferenceSource? = null,
    val options: List<NodeSettingOption> = emptyList(),
    /** Sub-fields of a [SettingControl.GROUP_LIST] row — self-referential, which is legal for a class. */
    val fields: List<NodeSettingSlot> = emptyList(),
    val itemLabel: String? = null,
    val group: String? = null,
    val visibleWhenSetting: String? = null,
    val visibleWhenEquals: String? = null,
)

/**
 * Static palette + composition metadata for a node type, built at compile time by the KSP node
 * registrar from the node's annotation — never runtime reflection. Drives the Vue Flow palette
 * (projected to GraphQL) and slot-constraint enforcement in the executor and the editor.
 *
 * A node's output is declared on [outputs]: a single output is one slot (conventionally `"out"`, drawn
 * as the anonymous handle); no slots means a single implicit output of unknown kind ([SlotKind.ANY]);
 * 2+ slots (or any error port) are named, wireable ports. A node's editable form is declared on
 * [settings]: each entry tells the editor which control to render for one settings key.
 */
class NodeDescriptor(
    val key: String,
    val label: String,
    val category: NodeCategory,
    /**
     * Top-level organizational group for the palette and node browser (e.g. `"WorkOps"`), declared
     * on the node's `@PipelineNodeType`. `null` = unannotated — consumers fall back to bucketing by
     * [category]. Orthogonal to [category], which stays the functional kind (the node's chrome).
     */
    val group: String? = null,
    /** Second-level grouping under [group] (e.g. `"Releases"`); `null` = directly under [group]. */
    val subgroup: String? = null,
    val inputs: List<NodeInputSlot> = emptyList(),
    val variadic: Boolean = false,
    /** The node's output ports; empty = a single implicit output of unknown kind. */
    val outputs: List<NodeOutputSlot> = emptyList(),
    val description: String = "",
    /** The node's editable settings, in display order; empty = no configuration. */
    val settings: List<NodeSettingSlot> = emptyList(),
)
