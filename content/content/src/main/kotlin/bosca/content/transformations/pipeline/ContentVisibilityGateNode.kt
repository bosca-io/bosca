package bosca.content.transformations.pipeline

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.search.Indexable
import bosca.security.model.PermissibleEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Routes a content entity (any [PermissibleEntity] — Metadata, Collection, Profile, …) to the
 * **visible** or **blocked** port based on the configurable visibility toggles plus an optional JSONata
 * [condition] — the same rules the Build Search Document nodes apply, but reusable in *any* pipeline,
 * not just indexing. The entity passes through unchanged on whichever port is taken; the executor
 * skips the other branch.
 *
 * Each toggle is enforced only when on, so the gate can be set to pass unpublished or non-public
 * content. `searchable` is read when the entity is also [Indexable]; otherwise it is treated as
 * searchable so a non-Indexable entity is not blocked by that toggle.
 */
@PipelineNodeType(
    category = NodeCategory.ROUTE,
    label = "Visibility Gate",
    description = "Routes a content entity to the visible or blocked branch by public/published/searchable/deleted and an optional constraint.",
    group = "Content",
    subgroup = "Search Documents",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Content entity",
            description = "A content entity (Metadata, Collection, Profile, …) to check.",
        ),
    ],
    // The entity passes through unchanged, so both ports emit an object; declaring OBJECT (rather than
    // the default ANY) keeps the connection validator and editor in agreement so routing edges to an
    // object input aren't dropped on reload.
    outputs = [
        OutputSlot(name = "visible", kind = SlotKind.OBJECT),
        OutputSlot(name = "blocked", kind = SlotKind.OBJECT),
    ],
    settings = [
        SettingSlot(name = "requirePublic", control = SettingControl.BOOLEAN, label = "Public", default = "true", group = "Visible only when"),
        SettingSlot(name = "requirePublished", control = SettingControl.BOOLEAN, label = "Published", default = "true", group = "Visible only when"),
        SettingSlot(name = "requireSearchable", control = SettingControl.BOOLEAN, label = "Searchable", default = "true", group = "Visible only when"),
        SettingSlot(name = "excludeDeleted", control = SettingControl.BOOLEAN, label = "Not deleted", default = "true", group = "Visible only when"),
        SettingSlot(
            name = "condition", control = SettingControl.TEXT, label = "Extra constraint (JSONata, optional)", mono = true,
            placeholder = "e.g. attributes.indexable = true",
            description = "Routes the entity to the visible branch when it passes every enabled check (and the optional constraint), otherwise to blocked. The entity passes through unchanged.",
        ),
    ],
)
@Serializable
@SerialName("content.visibilityGate")
class ContentVisibilityGateNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Block entities that are not public. */
    val requirePublic: Boolean = true,
    /** Block entities that are not published. */
    val requirePublished: Boolean = true,
    /** Block entities that are not searchable (only applies when the entity is Indexable). */
    val requireSearchable: Boolean = true,
    /** Block deleted entities. */
    val excludeDeleted: Boolean = true,
    /** Optional JSONata predicate over the entity; when set and false, the entity is blocked. */
    val condition: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = inputs.first
            ?: error("Visibility Gate node '${name.ifBlank { id }}' requires an entity")
        val entity = input.value as? PermissibleEntity<*>
            ?: error(
                "Visibility Gate node '${name.ifBlank { id }}' requires a content entity " +
                    "(got ${input.typeName ?: "a non-entity value"})",
            )
        val searchable = (input.value as? Indexable)?.isSearchable ?: true
        val visible = ContentVisibility.passes(
            public = entity.public, published = entity.isPublished,
            searchable = searchable, deleted = entity.isDeleted,
            requirePublic = requirePublic, requirePublished = requirePublished,
            requireSearchable = requireSearchable, excludeDeleted = excludeDeleted,
        ) && (condition.isBlank() || ContentVisibility.matches(condition, input.encode(context.json)))
        return input.onPort(if (visible) "visible" else "blocked")
    }
}
