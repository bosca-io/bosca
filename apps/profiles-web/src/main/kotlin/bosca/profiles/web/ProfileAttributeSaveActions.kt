package bosca.profiles.web

import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.ProfileVisibility

internal suspend fun addTextProfileAttribute(
    model: ProfileEditorModel,
    typeId: String,
    value: String,
    visibility: String,
    ctx: RenderContext,
) = mutateProfileAttribute(model) {
    persistProfileAttribute(
        model,
        "",
        typeId,
        profileTextAttributeValue(model, typeId, value),
        ProfileVisibility.valueOf(visibility),
        ctx,
    )
    model.selectedTypeId = ""
}

internal suspend fun addBooleanProfileAttribute(
    model: ProfileEditorModel,
    typeId: String,
    value: Boolean,
    visibility: String,
    ctx: RenderContext,
) = mutateProfileAttribute(model) {
    persistProfileAttribute(
        model,
        "",
        typeId,
        profileBooleanAttributeValue(model, typeId, value),
        ProfileVisibility.valueOf(visibility),
        ctx,
    )
    model.selectedTypeId = ""
}
