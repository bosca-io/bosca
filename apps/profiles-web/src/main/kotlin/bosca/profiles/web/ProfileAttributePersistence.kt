package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.DeleteProfileAttribute
import bosca.profiles.web.graphql.ProfileAttributeInput
import bosca.profiles.web.graphql.ProfileDetails
import bosca.profiles.web.graphql.ProfileVisibility
import bosca.profiles.web.graphql.SaveProfileAttribute
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

private val profileAttributeLog = LoggerFactory.getLogger("bosca.profiles.web.ProfileAttributePersistence")

@Serializable
internal data class ProfileAttributeEdit(
    val id: String,
    val typeId: String,
    val value: String,
    val visibility: String,
)

internal fun profileAttributeInputs(
    model: ProfileEditorModel,
    payload: String,
): List<ProfileAttributeInput> {
    val edits = Json.decodeFromString<List<ProfileAttributeEdit>>(payload)
    require(edits.map { it.id }.distinct().size == edits.size) { "An attribute was submitted more than once." }
    return edits.map { edit ->
        val existing = model.attributes.firstOrNull { it.id == edit.id }
            ?: error("Unknown profile attribute")
        require(!existing.protected) { "This attribute is managed by the platform." }
        require(existing.typeId == edit.typeId) { "The attribute type does not match." }
        model.requireEditableProfileAttributeType(edit.typeId)
        val parsed = when {
            !existing.hasField -> Json.parseToJsonElement(edit.value.trim())
            existing.field.boolean -> profileBooleanAttributeValue(
                model,
                edit.typeId,
                edit.value.toBooleanStrictOrNull()
                    ?: throw IllegalArgumentException("${existing.field.label} must be true or false."),
            )
            else -> profileTextAttributeValue(model, edit.typeId, edit.value)
        }
        ProfileAttributeInput(
            id = existing.id,
            attributes = parsed,
            confidence = existing.confidence,
            expiration = existing.expires.ifBlank { null },
            priority = existing.priority,
            source = existing.source,
            typeId = existing.typeId,
            visibility = ProfileVisibility.valueOf(edit.visibility),
        )
    }
}

internal suspend fun saveAllProfileAttributes(
    model: ProfileEditorModel,
    payload: String,
    ctx: RenderContext,
) = mutateProfileAttribute(model) {
    val inputs = profileAttributeInputs(model, payload)
    require(inputs.isNotEmpty()) { "There are no editable attributes to save." }
    ctx.gql.execute(
        SaveProfileAttribute,
        SaveProfileAttribute.Variables(id = model.id, attributes = inputs),
    )
    reloadProfileAttributes(model, ctx)
    model.message = if (inputs.size == 1) "Profile attribute saved." else "Profile attributes saved."
    model.failed = false
    model.messageId += 1
}

internal suspend fun persistProfileAttribute(
    model: ProfileEditorModel,
    attributeId: String,
    typeId: String,
    parsed: JsonElement,
    visibility: ProfileVisibility,
    ctx: RenderContext,
) {
    model.requireEditableProfileAttributeType(typeId)
    val existing = model.attributes.firstOrNull { it.id == attributeId }
    ctx.gql.execute(
        SaveProfileAttribute,
        SaveProfileAttribute.Variables(
            id = model.id,
            attributes = listOf(
                ProfileAttributeInput(
                    id = attributeId.ifBlank { null },
                    attributes = parsed,
                    confidence = existing?.confidence ?: 100,
                    expiration = existing?.expires?.ifBlank { null },
                    priority = existing?.priority ?: 100,
                    source = existing?.source ?: "user-input",
                    typeId = typeId,
                    visibility = visibility,
                ),
            ),
        ),
    )
    reloadProfileAttributes(model, ctx)
    model.message = if (existing == null) "Attribute added." else "Attribute saved."
    model.failed = false
    model.messageId += 1
}

internal suspend fun deleteProfileAttribute(
    model: ProfileEditorModel,
    attributeId: String,
    ctx: RenderContext,
) = mutateProfileAttribute(model) {
    val attribute = model.attributes.firstOrNull { it.id == attributeId } ?: return@mutateProfileAttribute
    require(!attribute.protected) { "This attribute is managed by the platform." }
    ctx.gql.execute(
        DeleteProfileAttribute,
        DeleteProfileAttribute.Variables(profileId = model.id, attributeId = attributeId),
    )
    model.attributes = model.attributes.filterNot { it.id == attributeId }
    model.message = "Attribute removed."
    model.failed = false
    model.messageId += 1
}

private suspend fun reloadProfileAttributes(model: ProfileEditorModel, ctx: RenderContext) {
    val profile = ctx.gql.execute(ProfileDetails, Unit).profiles.current.orEmpty()
        .firstOrNull { it.id == model.id } ?: error("Profile could not be reloaded")
    model.attributes = profile.attributes.toAccountAttributeRows()
}

internal suspend fun mutateProfileAttribute(model: ProfileEditorModel, action: suspend () -> Unit) {
    try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        profileAttributeLog.warn("Profile attribute update failed for {}: {}", model.id, e.toString())
        model.message = e.message ?: "We couldn't save that attribute."
        model.failed = true
        model.messageId += 1
    }
}
