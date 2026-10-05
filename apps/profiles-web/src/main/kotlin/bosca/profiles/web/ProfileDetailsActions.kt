package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.EditProfile
import bosca.profiles.web.graphql.ProfileInput
import bosca.profiles.web.graphql.ProfileVisibility
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

private val profileDetailsLog = LoggerFactory.getLogger("bosca.profiles.web.ProfileDetailsActions")

internal suspend fun saveProfileDetails(
    model: ProfileEditorModel,
    name: String,
    visibility: String,
    searchable: Boolean,
    ctx: RenderContext,
) {
    try {
        require(name.isNotBlank()) { "Display name is required." }
        val result = ctx.gql.execute(
            EditProfile,
            EditProfile.Variables(
                id = model.id,
                profile = ProfileInput(
                    slug = model.slug.ifBlank { null },
                    name = name.trim(),
                    attributes = emptyList(),
                    visibility = ProfileVisibility.valueOf(visibility),
                    searchable = searchable,
                ),
            ),
        ).profiles.edit ?: error("Profile could not be saved")
        model.name = result.name
        model.slug = result.slug.orEmpty()
        model.visibility = result.visibility
        model.searchable = result.searchable
        model.modified = result.modified
        model.message = "Profile saved."
        model.failed = false
        model.messageId += 1
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        profileDetailsLog.warn("Profile update failed for {}: {}", model.id, e.toString())
        model.message = e.message ?: "We couldn't save your profile."
        model.failed = true
        model.messageId += 1
    }
}
