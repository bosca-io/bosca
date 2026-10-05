package bosca.profile.profile.pipeline

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingOption
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The operations [SetProfileAttributeNode] accepts; an explicit (non-companion) constant so the
 *  `@Serializable` companion that KSP needs for `serializer()` stays the generated one. */
private val SET_PROFILE_ATTRIBUTE_OPERATIONS = setOf("add", "update", "delete")

/**
 * Action contributed by `social/profile`: sets a profile attribute via [ProfileAttributeService].
 * The [operation] chooses the effect and which input identifies the target:
 *  - `add` — inserts a new attribute of [typeId] on the profile from the `profile` input (a UUID),
 *    carrying the `value` input as its JSON.
 *  - `update` — replaces the `value` of the existing attribute named by the `attribute` input (a
 *    UUID), preserving its type, owner, and verification proof; fails if no such attribute exists.
 *  - `delete` — removes the attribute named by the `attribute` input (a UUID).
 *
 * `add` reads the profile id; `update`/`delete` read the attribute id — feed either from a Get Id
 * node. A dry run records the would-be change and mutates nothing. The output is a small JSON
 * receipt (the affected attribute id, plus what changed).
 *
 * Contributed to the engine purely by the `@PipelineNodeType` annotation — no engine changes.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Profile Attribute",
    description = "Add (by profile id), or update/delete (by attribute id) a profile attribute.",
    group = "Social",
    subgroup = "Profiles",
    inputs = [
        InputSlot(
            name = "profile",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile to add the attribute to — a UUID (add only).",
            required = false,
        ),
        InputSlot(
            name = "attribute",
            kind = SlotKind.UUID,
            typeLabel = "Attribute id",
            description = "The attribute to update or delete — a UUID (update/delete).",
            required = false,
        ),
        InputSlot(
            // ANY (not OBJECT): the value is arbitrary JSON, commonly shaped by a JSONata node whose
            // output kind is unknown — a typed slot would refuse that wire.
            name = "value",
            typeLabel = "Attribute value",
            description = "The JSON value to store as the attribute (add/update).",
            required = false,
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Result",
            description = "The affected attribute id and what changed.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "operation", control = SettingControl.ENUM, label = "Operation", default = "add",
            options = [SettingOption("add", "Add"), SettingOption("update", "Update"), SettingOption("delete", "Delete")],
        ),
        SettingSlot(
            name = "typeId", control = SettingControl.REFERENCE, reference = ReferenceSource.ATTRIBUTE_TYPE,
            label = "Attribute type", required = true, placeholder = "Pick an attribute type…",
            visibleWhenSetting = "operation", visibleWhenEquals = "add",
            description = "Adds a new attribute of this type to the profile on the 'profile' input, storing the 'value' input as its JSON.",
        ),
        SettingSlot(
            name = "visibility", control = SettingControl.ENUM, label = "Visibility", default = "USER",
            visibleWhenSetting = "operation", visibleWhenEquals = "add",
            options = [
                SettingOption("SYSTEM", "System"), SettingOption("USER", "User"), SettingOption("FRIENDS", "Friends"),
                SettingOption("FRIENDS_OF_FRIENDS", "Friends of friends"), SettingOption("PUBLIC", "Public"),
            ],
        ),
        SettingSlot(
            name = "confidence", control = SettingControl.INTEGER, label = "Confidence (0–100)", default = "100", placeholder = "100",
            visibleWhenSetting = "operation", visibleWhenEquals = "add",
        ),
        SettingSlot(
            name = "priority", control = SettingControl.INTEGER, label = "Priority", default = "0", placeholder = "0",
            visibleWhenSetting = "operation", visibleWhenEquals = "add",
        ),
        SettingSlot(
            name = "source", control = SettingControl.TEXT, label = "Source", default = "pipeline", placeholder = "pipeline",
            visibleWhenSetting = "operation", visibleWhenEquals = "add",
        ),
    ],
)
@Serializable
@SerialName("profile.setAttribute")
class SetProfileAttributeNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** What to do: `add` (by profile id), `update`, or `delete` (both by attribute id). */
    val operation: String = "add",
    /** The attribute type id to create, e.g. `bosca.profiles.email` (add only). */
    val typeId: String = "",
    /** Visibility recorded on an added attribute (add only). */
    val visibility: ProfileVisibility = ProfileVisibility.USER,
    /** Confidence (0–100) recorded on an added attribute (add only). */
    val confidence: Int = 100,
    /** Priority ranking recorded on an added attribute (add only). */
    val priority: Int = 0,
    /** Provenance recorded on an added attribute (add only). */
    val source: String = "pipeline",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    // A dry run records the would-be change and mutates nothing — side effects never fire.
    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        val op = resolveOperation(label)
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "setProfileAttribute")
            put("operation", op)
        })
        return SetProfileAttributeNodeSerializer.serialize(buildJsonObject {
            put("operation", op)
            put("dryRun", true)
        })
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        val op = resolveOperation(label)
        // Note: the generated Inputs type must not appear in the private helpers' signatures — KSP
        // validates declaration signatures before this node's serializer is generated.
        val input = SetProfileAttributeNodeSerializer.deserialize(context, inputs)

        val service = provide<ProfileAttributeService>()
        return when (op) {
            "add" -> add(label, service, input.profile, input.value, context)
            "update" -> update(label, service, input.attribute, input.value, context)
            else -> delete(label, service, input.attribute)
        }
    }

    private fun resolveOperation(label: String): String {
        val op = operation.trim().lowercase()
        require(op in SET_PROFILE_ATTRIBUTE_OPERATIONS) {
            "Set Profile Attribute node '$label': unknown operation '$operation' (expected add, update, or delete)"
        }
        return op
    }

    private suspend fun add(
        label: String,
        service: ProfileAttributeService,
        profile: UUID?,
        rawValue: PipelineValue?,
        context: PipelineContext,
    ): PipelineValue {
        require(typeId.isNotBlank()) { "Set Profile Attribute node '$label': add requires a typeId" }
        val profileId = profile
            ?: error("Set Profile Attribute node '$label': add requires a profile UUID on its 'profile' input")
        val value = rawValue?.encode(context.json)
            ?: error("Set Profile Attribute node '$label': add requires a 'value' input")
        val saved = service.addAttributes(
            profileId,
            listOf(
                ProfileAttributeInput(
                    id = UUID.NIL, // NIL inserts a new attribute
                    typeId = typeId,
                    visibility = visibility,
                    confidence = confidence,
                    priority = priority,
                    source = source,
                    attributes = value,
                ),
            ),
        ).firstOrNull()
            ?: error("Set Profile Attribute node '$label': attribute type '$typeId' is protected or could not be saved")
        return receipt("add", saved.profile.toString(), saved.id.toString(), saved.typeId)
    }

    private suspend fun update(
        label: String,
        service: ProfileAttributeService,
        attribute: UUID?,
        rawValue: PipelineValue?,
        context: PipelineContext,
    ): PipelineValue {
        val attributeId = attribute
            ?: error("Set Profile Attribute node '$label': update requires an attribute UUID on its 'attribute' input")
        val value = rawValue?.encode(context.json)
            ?: error("Set Profile Attribute node '$label': update requires a 'value' input")
        val existing = service.getById(attributeId)
            ?: error("Set Profile Attribute node '$label': no attribute $attributeId to update")
        // Replace only the value; preserve the attribute's type, owner, and (via addAttributes) its
        // verification proof when the proven value is unchanged.
        val saved = service.addAttributes(
            existing.profile,
            listOf(
                ProfileAttributeInput(
                    id = existing.id,
                    typeId = existing.typeId,
                    visibility = existing.visibility,
                    confidence = existing.confidence,
                    priority = existing.priority,
                    source = existing.source,
                    attributes = value,
                ),
            ),
        ).firstOrNull()
            ?: error("Set Profile Attribute node '$label': attribute $attributeId could not be updated")
        return receipt("update", saved.profile.toString(), saved.id.toString(), saved.typeId)
    }

    private suspend fun delete(
        label: String,
        service: ProfileAttributeService,
        attribute: UUID?,
    ): PipelineValue {
        val attributeId = attribute
            ?: error("Set Profile Attribute node '$label': delete requires an attribute UUID on its 'attribute' input")
        // deleteAttribute returns the owning profile, or NIL when nothing matched.
        val profileId = service.deleteAttribute(attributeId)
        val found = profileId != UUID.NIL
        return SetProfileAttributeNodeSerializer.serialize(buildJsonObject {
            put("operation", "delete")
            put("attributeId", attributeId.toString())
            put("found", found)
            if (found) put("profileId", profileId.toString())
        })
    }

    private fun receipt(op: String, profileId: String, attributeId: String, typeId: String): PipelineValue =
        SetProfileAttributeNodeSerializer.serialize(buildJsonObject {
            put("operation", op)
            put("profileId", profileId)
            put("attributeId", attributeId)
            put("typeId", typeId)
        })
}
