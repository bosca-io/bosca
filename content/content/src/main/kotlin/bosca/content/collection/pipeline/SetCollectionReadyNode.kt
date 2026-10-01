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

/**
 * Action node contributed by `content`: marks an inbound [Collection] ready for publishing (or revokes
 * that readiness) via [CollectionService].
 *
 * When [ready] is true it calls `setReady`, recording the run's principal as the approver — so the run
 * must carry an authenticated principal; when false it calls `setNotReady`. A blank [languageTag]
 * targets the base collection; a tag scopes readiness to that language variant. The node passes the
 * *refreshed* collection through its `out` port for chaining. Under [PipelineContext.dryRun] it records
 * the intended action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Collection Ready",
    description = "Marks a collection ready for publishing, or revokes its ready status.",
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
            name = "ready", control = SettingControl.BOOLEAN, label = "Ready", default = "true",
            description = "On marks the collection ready for publishing (recording the run's principal as approver); off revokes ready.",
        ),
        SettingSlot(
            name = "languageTag", control = SettingControl.TEXT, label = "Language", placeholder = "Base collection",
            description = "Optional language tag to mark only a specific variant ready; blank targets the base collection.",
        ),
    ],
)
@Serializable
@SerialName("collection.setReady")
class SetCollectionReadyNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** True marks the collection ready (recording the run principal); false revokes ready. */
    val ready: Boolean = true,
    /** Optional language tag to scope readiness to a variant; blank targets the base collection. */
    val languageTag: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so a missing
        // required input must not fail it.
        val input = inputs.first
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setCollectionReady")
            put("ready", ready)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collection = SetCollectionReadyNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<CollectionService>()
        if (ready) {
            val principal = context.authentication.principal()?.asPrincipal()
                ?: error("Set Collection Ready node '$label' requires an authenticated principal to mark content ready")
            service.setReady(collection.id, principal, languageTag.ifBlank { null })
        } else {
            service.setNotReady(collection)
        }
        val fresh = service.getById(collection.id)
            ?: error("Set Collection Ready node '$label': collection ${collection.id} not found after update")
        return SetCollectionReadyNodeSerializer.serialize(fresh)
    }
}
