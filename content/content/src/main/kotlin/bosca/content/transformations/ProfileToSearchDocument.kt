package bosca.content.transformations

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.search.IndexStorageSystem
import bosca.search.model.ProfileSearchContext
import bosca.search.model.SearchTransformConfiguration
import bosca.serialization.JsonConverter.toAny
import bosca.serialization.JsonConverter.toJsonElement
import bosca.slug.service.SlugService
import bosca.transformations.Transformation
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProfileToSearchDocumentConfiguration(
    val excludeTypes: Set<String> = emptySet(),
    val excludeContentTypePrefix: List<String> = emptyList()
)

class ProfileToSearchDocument(
    private val profileService: ProfileService,
    private val organizationService: OrganizationService,
    private val slugs: SlugService,
    private val json: Json,
    private val configuration: ProfileToSearchDocumentConfiguration,
    private val configurationService: ConfigurationService,
) : Transformation<IndexStorageSystem, Profile, JsonElement?> {

    override suspend fun transform(context: IndexStorageSystem, item: Profile): JsonElement? {
        if (context.name != "Admin Search Index") {
            val type = item.attributes?.takeIf { it is JsonObject }?.jsonObject?.get("type")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            if (type != null && type in configuration.excludeTypes) return null
            val contentType = if (item.type == ProfileType.ORGANIZATION) "bosca/v-profile-organization" else "bosca/v-profile-generic"
            if (configuration.excludeContentTypePrefix.any { contentType.startsWith(it) }) return null
        }
        val result = toContext(context, item)
        val searchConfiguration = configurationService.getValueAs<SearchTransformConfiguration>("search", json)
        val expression = searchConfiguration?.expressions?.profile
        if (expression.isNullOrBlank()) return json.encodeToJsonElement(result)
        return Jsonata.jsonata(expression).evaluate(json.encodeToJsonElement(result).toAny()).toJsonElement()
    }

    suspend fun toContext(context: IndexStorageSystem, item: Profile): ProfileSearchContext {
        val isOrganization = item.type == ProfileType.ORGANIZATION
        val organization = if (isOrganization) {
            organizationService.getOrganizationByProfile(item.id)
        } else {
            null
        }
        val organizations = item.principal?.let { principal -> organizationService.getMemberOrganizations(principal) }?.map { organizationService.getOrganization(it.organizationId) }
        val memberCount = if (organization != null) {
            organizationService.getMemberCount(organization.id)
        } else {
            0L
        }
        return ProfileSearchContext(
            storage = context,
            profile = item,
            contentType = if (isOrganization) "bosca/v-profile-organization" else "bosca/v-profile-generic",
            organization = organization,
            organizations = organizations ?: emptyList(),
            memberCount = memberCount,
            attributes = profileService.getAttributes(item.id),
            slug = slugs.getProfileSlug(item.id) ?: ""
        )
    }
}