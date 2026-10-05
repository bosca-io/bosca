package bosca.profiles.web

import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.ProfileVisibility
import kotlinx.serialization.Serializable

@Serializable
class ProfileEditorModel(
    val id: String,
    var name: String,
    var slug: String,
    var visibility: ProfileVisibility,
    var searchable: Boolean,
    val created: String,
    var modified: String,
    var attributes: List<ProfileAttributeRow>,
    val attributeTypes: List<ProfileAttributeTypeRow>,
    var selectedTypeId: String = "",
    var message: String? = null,
    var failed: Boolean = false,
    var messageId: Long = 0,
) {
    val statusClass: String get() = if (failed) "status error" else "status ok"
    val addableAttributeTypes: List<ProfileAttributeTypeRow>
        get() = attributeTypes.filter {
            it.selectable && !it.protected && it.visibility != ProfileVisibility.SYSTEM
        }
    val selectedAttributeType: ProfileAttributeTypeRow
        get() = addableAttributeTypes.firstOrNull { it.id == selectedTypeId }
            ?: ProfileAttributeTypeRow(
                id = "",
                name = "",
                description = "",
                visibility = ProfileVisibility.PUBLIC,
                protected = false,
                field = ProfileAttributeFieldRow(),
            )
    val hasSelectedAttributeType: Boolean get() = selectedAttributeType.id.isNotBlank()

    suspend fun save(
        name: String,
        visibility: String,
        searchable: Boolean,
        ctx: RenderContext,
    ) = saveProfileDetails(this, name, visibility, searchable, ctx)

    fun chooseAttributeType(typeId: String) {
        selectedTypeId = addableAttributeTypes.firstOrNull { it.id == typeId }?.id.orEmpty()
    }

    suspend fun saveAttributes(attributes: String, ctx: RenderContext) =
        saveAllProfileAttributes(this, attributes, ctx)

    suspend fun addTextAttribute(
        typeId: String,
        value: String,
        visibility: String,
        ctx: RenderContext,
    ) = addTextProfileAttribute(this, typeId, value, visibility, ctx)

    suspend fun addBooleanAttribute(
        typeId: String,
        value: Boolean,
        visibility: String,
        ctx: RenderContext,
    ) = addBooleanProfileAttribute(this, typeId, value, visibility, ctx)

    suspend fun deleteAttribute(attributeId: String, ctx: RenderContext) =
        deleteProfileAttribute(this, attributeId, ctx)
}
