package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class SystemHubSpotPreview(
    val admin: Boolean = false, var query: String = "",
    var profiles: List<SystemHubSpotProfilesData.Search.Search.Documents.Profile> = emptyList(),
    var profileId: String = "", var profileName: String = "", var profileType: String = "",
    var context: JsonElement? = null, val feedback: SystemFeedback = SystemFeedback(),
) {
    suspend fun search(value: String, ctx: RenderContext) = feedback.perform {
        query = value; profiles = emptyList()
        require(admin) { "Administrator access is required." }
        if (value.length < 2) return@perform
        profiles = ctx.gql.execute(SystemHubSpotProfiles, SystemHubSpotProfiles.Variables(value)).search.search?.documents.orEmpty().mapNotNull { it.profile }
    }

    suspend fun select(id: String, ctx: RenderContext) = feedback.perform {
        profileId = id; context = null; profileName = ""; profileType = ""
        require(admin) { "Administrator access is required." }
        if (id.isBlank()) return@perform
        val profile = ctx.gql.execute(SystemHubSpotContext, SystemHubSpotContext.Variables(id)).profiles.profile ?: error("Profile not found.")
        profileName = profile.name; profileType = profile.type.name; context = profile.thirdparty?.context
    }

    suspend fun sync(all: Boolean, ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access is required." }
        if (all) {
            check(ctx.gql.execute(SystemHubSpotSyncAll, Unit).profiles.profile.thirdparty?.syncAll == true) { "The sync request was not accepted." }
            feedback.message = "Sync started for all profiles."
        } else {
            require(profileId.isNotBlank()) { "Choose a profile first." }
            check(ctx.gql.execute(SystemHubSpotSync, SystemHubSpotSync.Variables(profileId)).profiles.profile.thirdparty?.sync == true) { "The sync request was not accepted." }
            feedback.message = "Sync started for $profileName."
        }
    }
}
