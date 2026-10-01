package bosca.profiles.web

import bosca.profiles.web.graphql.ProfileDetailsData
import bosca.profiles.web.graphql.ProfileVisibility

internal fun List<ProfileDetailsData.Profiles.Current.Attributes>.toAccountAttributeRows(): List<ProfileAttributeRow> =
    filterNot { attribute ->
        attribute.visibility == ProfileVisibility.SYSTEM || attribute.type.visibility == ProfileVisibility.SYSTEM
    }.map { it.toProfileAttributeRow() }

internal fun ProfileDetailsData.Profiles.Current.Attributes.toProfileAttributeRow() = ProfileAttributeRow(
    id = id,
    typeId = typeId,
    typeName = type.name,
    description = type.description,
    value = attributes.asEditableJson(),
    source = source,
    priority = priority,
    confidence = confidence,
    visibility = visibility,
    expires = expires.orEmpty(),
    protected = type.protected,
    verified = verified,
    field = profileAttributeField(type.formSchema?.schema, type.formSchema?.uiSchema, attributes),
)
