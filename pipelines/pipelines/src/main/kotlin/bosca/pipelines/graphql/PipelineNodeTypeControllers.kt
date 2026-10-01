package bosca.pipelines.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeFieldDescriptor
import bosca.pipelines.node.NodeInputSlot
import bosca.pipelines.node.NodeOutputSlot
import bosca.pipelines.node.NodeSettingOption
import bosca.pipelines.node.NodeSettingSlot
import kotlinx.serialization.json.JsonElement

/** Field wiring for the `PipelineNodeType` GraphQL type (palette entries); source is [NodeDescriptor]. */
@TypeController(type = "PipelineNodeType")
class PipelineNodeTypeController : GraphQLController<NodeDescriptor> {

    @Field
    fun key(source: NodeDescriptor): String = source.key

    @Field
    fun label(source: NodeDescriptor): String = source.label

    @Field
    fun category(source: NodeDescriptor): NodeCategory = source.category

    @Field
    fun group(source: NodeDescriptor): String? = source.group

    @Field
    fun subgroup(source: NodeDescriptor): String? = source.subgroup

    @Field
    fun description(source: NodeDescriptor): String = source.description

    @Field
    fun inputs(source: NodeDescriptor): List<NodeInputSlot> = source.inputs

    @Field
    fun outputs(source: NodeDescriptor): List<NodeOutputSlot> = source.outputs

    @Field
    fun settings(source: NodeDescriptor): List<NodeSettingSlot> = source.settings
}

/** Field wiring for the `PipelineNodeInputSlot` GraphQL type (one typed input port); source is [NodeInputSlot]. */
@TypeController(type = "PipelineNodeInputSlot")
class PipelineNodeInputSlotController : GraphQLController<NodeInputSlot> {

    @Field
    fun name(source: NodeInputSlot): String = source.name

    @Field
    fun kind(source: NodeInputSlot): SlotKind = source.kind

    @Field
    fun typeLabel(source: NodeInputSlot): String = source.typeLabel

    @Field
    fun description(source: NodeInputSlot): String? = source.description

    @Field
    fun type(source: NodeInputSlot): String? = source.type

    @Field
    fun schema(source: NodeInputSlot): JsonElement? = source.schema

    @Field
    fun required(source: NodeInputSlot): Boolean = source.required

    @Field
    fun structure(source: NodeInputSlot): List<NodeFieldDescriptor>? = source.structure
}

/** Field wiring for the `PipelineNodeOutputSlot` GraphQL type (one named output port); source is [NodeOutputSlot]. */
@TypeController(type = "PipelineNodeOutputSlot")
class PipelineNodeOutputSlotController : GraphQLController<NodeOutputSlot> {

    @Field
    fun name(source: NodeOutputSlot): String = source.name

    @Field
    fun kind(source: NodeOutputSlot): SlotKind = source.kind

    @Field
    fun error(source: NodeOutputSlot): Boolean = source.error

    @Field
    fun type(source: NodeOutputSlot): String? = source.type

    @Field
    fun typeLabel(source: NodeOutputSlot): String? = source.typeLabel

    @Field
    fun description(source: NodeOutputSlot): String? = source.description

    @Field
    fun structure(source: NodeOutputSlot): List<NodeFieldDescriptor>? = source.structure
}

/** Field wiring for the `PipelineNodeFieldDescriptor` GraphQL type (one field of a typed slot's object); source is [NodeFieldDescriptor]. */
@TypeController(type = "PipelineNodeFieldDescriptor")
class PipelineNodeFieldDescriptorController : GraphQLController<NodeFieldDescriptor> {

    @Field
    fun name(source: NodeFieldDescriptor): String = source.name

    @Field
    fun type(source: NodeFieldDescriptor): String = source.type

    @Field
    fun fields(source: NodeFieldDescriptor): List<NodeFieldDescriptor>? = source.fields
}

/** Field wiring for the `PipelineNodeSettingSlot` GraphQL type (one editable setting); source is [NodeSettingSlot]. */
@TypeController(type = "PipelineNodeSettingSlot")
class PipelineNodeSettingSlotController : GraphQLController<NodeSettingSlot> {

    @Field
    fun name(source: NodeSettingSlot): String = source.name

    @Field
    fun control(source: NodeSettingSlot): SettingControl = source.control

    @Field
    fun label(source: NodeSettingSlot): String? = source.label

    @Field
    fun description(source: NodeSettingSlot): String? = source.description

    @Field
    fun placeholder(source: NodeSettingSlot): String? = source.placeholder

    @Field
    fun default(source: NodeSettingSlot): String? = source.default

    @Field
    fun required(source: NodeSettingSlot): Boolean = source.required

    @Field
    fun secret(source: NodeSettingSlot): Boolean = source.secret

    @Field
    fun mono(source: NodeSettingSlot): Boolean = source.mono

    @Field
    fun language(source: NodeSettingSlot): String? = source.language

    @Field
    fun reference(source: NodeSettingSlot): ReferenceSource? = source.reference

    @Field
    fun options(source: NodeSettingSlot): List<NodeSettingOption> = source.options

    @Field
    fun fields(source: NodeSettingSlot): List<NodeSettingSlot> = source.fields

    @Field
    fun itemLabel(source: NodeSettingSlot): String? = source.itemLabel

    @Field
    fun group(source: NodeSettingSlot): String? = source.group

    @Field
    fun visibleWhenSetting(source: NodeSettingSlot): String? = source.visibleWhenSetting

    @Field
    fun visibleWhenEquals(source: NodeSettingSlot): String? = source.visibleWhenEquals
}

/** Field wiring for the `PipelineNodeSettingOption` GraphQL type (one ENUM/REFERENCE choice); source is [NodeSettingOption]. */
@TypeController(type = "PipelineNodeSettingOption")
class PipelineNodeSettingOptionController : GraphQLController<NodeSettingOption> {

    @Field
    fun value(source: NodeSettingOption): String = source.value

    @Field
    fun label(source: NodeSettingOption): String? = source.label
}
