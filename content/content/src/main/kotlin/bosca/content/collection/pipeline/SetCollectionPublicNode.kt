package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingOption
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The visibility targets [SetCollectionPublicNode] accepts; a file-level constant (never a companion)
 *  so the `@Serializable` companion KSP generates for `serializer()` is the only one. */
private val COLLECTION_PUBLIC_TARGETS = setOf("collection", "list", "supplementary")

/**
 * Action node contributed by `content`: toggles the public visibility of an inbound [Collection] via
 * [CollectionService]. The [target] chooses which visibility flag is set:
 *  - `collection` — the collection itself (`setPublic`).
 *  - `list` — its item list (`setPublicList`).
 *  - `supplementary` — its supplementary content (`setPublicSupplementary`).
 *
 * A blank [languageTag] targets the base collection; a tag scopes the change to that variant. The node
 * passes the *refreshed* collection through its `out` port for chaining. Under [PipelineContext.dryRun]
 * it records the intended action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Collection Public",
    description = "Sets the public visibility of a collection, its item list, or its supplementary content.",
    group = "Content",
    subgroup = "Collections",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Collection",
            type = Collection::class,
            description = "A Collection (e.g. from Get Collection).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Collection::class,
            typeLabel = "Collection",
            description = "The collection after the update, for chaining.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "target", control = SettingControl.ENUM, label = "Visibility of", default = "collection",
            options = [
                SettingOption("collection", "The collection"),
                SettingOption("list", "Its item list"),
                SettingOption("supplementary", "Its supplementary content"),
            ],
            description = "Which visibility flag to set.",
        ),
        SettingSlot(
            name = "public", control = SettingControl.BOOLEAN, label = "Public", default = "true",
            description = "On makes the chosen target publicly visible; off restricts it.",
        ),
        SettingSlot(
            name = "languageTag", control = SettingControl.TEXT, label = "Language", placeholder = "Base collection",
            description = "Optional language tag to set visibility for a specific variant; blank targets the base collection.",
        ),
    ],
)
@Serializable
@SerialName("collection.setPublic")
class SetCollectionPublicNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Which visibility flag to set: `collection`, `list`, or `supplementary`. */
    val target: String = "collection",
    /** True makes the chosen target publicly visible; false restricts it. */
    val public: Boolean = true,
    /** Optional language tag to scope the change to a variant; blank targets the base collection. */
    val languageTag: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so a missing
        // required input must not fail it.
        val input = inputs.first
        val t = resolveTarget()
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setCollectionPublic")
            put("target", t)
            put("public", public)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collection = SetCollectionPublicNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<CollectionService>()
        val lang = languageTag.ifBlank { null }
        when (resolveTarget()) {
            "collection" -> service.setPublic(collection.id, public, lang)
            "list" -> service.setPublicList(collection.id, public, lang)
            else -> service.setPublicSupplementary(collection.id, public, lang)
        }
        val fresh = service.getById(collection.id)
            ?: error("Set Collection Public node '$label': collection ${collection.id} not found after update")
        return SetCollectionPublicNodeSerializer.serialize(fresh)
    }

    private fun resolveTarget(): String {
        val t = target.trim().lowercase()
        require(t in COLLECTION_PUBLIC_TARGETS) {
            "Set Collection Public node '${name.ifBlank { id }}': unknown target '$target' (expected collection, list, or supplementary)"
        }
        return t
    }
}
