package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
class SystemHubSpotModel(
    val admin: Boolean = false, var draft: SystemHubSpotDraft = SystemHubSpotDraft(),
    var loaded: Boolean = false, var hasToken: Boolean = false,
    val feedback: SystemFeedback = SystemFeedback(),
) {
    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val value = systemConfigurationObject(ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables("hubspot")).configurations.configuration?.value)
        val expressions = systemConfigurationObject(value["expressions"])
        fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull
        fun ids(key: String) = (value[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        draft = SystemHubSpotDraft(
            associationTypeId = text("contactToCompanyAssociationTypeId") ?: "279",
            associationCategory = text("contactToCompanyAssociationCategory") ?: "HUBSPOT_DEFINED",
            listIds = ids("listIds"), subscriptionIds = ids("subscriptionIds"),
            genericExpression = expressions["generic"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            organizationExpression = expressions["organization"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        hasToken = !text("token").isNullOrBlank(); loaded = true
    }

    suspend fun save(token: String, clearToken: Boolean, associationTypeId: String, associationCategory: String, listIds: String, subscriptionIds: String, genericExpression: String, organizationExpression: String, ctx: RenderContext) = feedback.perform {
        draft = SystemHubSpotDraft(token, clearToken, associationTypeId, associationCategory, systemChoices(listIds), systemChoices(subscriptionIds), genericExpression, organizationExpression)
        require(loaded) { "Reload the page before saving settings." }
        val typeId = associationTypeId.toIntOrNull()
        require(typeId != null && typeId > 0) { "Association type ID must be a positive integer." }
        require(associationCategory.isNotBlank()) { "Enter an association category." }
        val current = ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables("hubspot")).configurations.configuration
        val value = systemConfigurationObject(current?.value).toMutableMap().apply {
            put("token", JsonPrimitive(systemStoredSecret(token, clearToken, get("token"))))
            put("contactToCompanyAssociationTypeId", JsonPrimitive(typeId)); put("contactToCompanyAssociationCategory", JsonPrimitive(associationCategory))
            put("listIds", JsonArray(draft.listIds.map(::JsonPrimitive))); put("subscriptionIds", JsonArray(draft.subscriptionIds.map(::JsonPrimitive)))
            put("expressions", JsonObject(systemConfigurationObject(get("expressions")).toMutableMap().apply {
                put("generic", JsonPrimitive(genericExpression)); put("organization", JsonPrimitive(organizationExpression))
            }))
        }
        ctx.gql.execute(SystemSetConfiguration, SystemSetConfiguration.Variables(ConfigurationInput(key = "hubspot", description = current?.description ?: "HubSpot Integration Configuration", value = JsonObject(value), public = false)))
        reload(ctx); feedback.message = "HubSpot settings saved. Restart the Atlas API and profile workers after changing the token."
    }
}

suspend fun systemHubSpot(): SystemHubSpotModel {
    val ctx = currentRenderContext()
    return SystemHubSpotModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
