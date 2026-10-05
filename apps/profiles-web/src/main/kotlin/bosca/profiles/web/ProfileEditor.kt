package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.profiles.web.graphql.ProfileDetails

suspend fun profileEditor(): ProfileEditorModel {
    val data = client().execute(ProfileDetails, Unit).profiles
    val profiles = data.current.orEmpty()
    val profile = profiles.firstOrNull { it.isPrimary } ?: profiles.firstOrNull()
        ?: error("No profile is associated with this account")
    return ProfileEditorModel(
        id = profile.id,
        name = profile.name,
        slug = profile.slug.orEmpty(),
        visibility = profile.visibility,
        searchable = profile.searchable,
        created = profile.created,
        modified = profile.modified,
        attributes = profile.attributes.toAccountAttributeRows(),
        attributeTypes = data.attributeTypes.all.map {
            ProfileAttributeTypeRow(
                id = it.id,
                name = it.name,
                description = it.description,
                visibility = it.visibility,
                protected = it.protected,
                field = profileAttributeField(it.formSchema?.schema, it.formSchema?.uiSchema),
            )
        },
    )
}
