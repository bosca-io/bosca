package bosca.content.transformations

import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchTransformConfiguration
import bosca.search.model.SearchTransformExpressions
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProfileToSearchDocumentTest {

    private val profileService = mockk<ProfileService>()
    private val organizationService = mockk<OrganizationService>()
    private val slugs = mockk<SlugService>()
    private val json = Json
    private val configuration = ProfileToSearchDocumentConfiguration()
    private val configurationService = mockk<ConfigurationService>()

    private val transformer = ProfileToSearchDocument(
        profileService,
        organizationService,
        slugs,
        json,
        configuration,
        configurationService
    )

    private val profileId = UUID.random()
    private val searchConfigId = UUID.random()

    private val profile = Profile(
        id = profileId,
        type = ProfileType.GENERIC,
        principal = null,
        collectionId = null,
        name = "Test Profile",
        visibility = ProfileVisibility.PUBLIC,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @Test
    fun `applies jsonata transformation if configured`() = runTest {
        coEvery { slugs.getProfileSlug(profileId) } returns "profile-slug"
        coEvery { profileService.getAttributes(any()) } returns emptyList()
        coEvery { configurationService.getByKey("search") } returns Configuration(searchConfigId, "search", "", false)
        coEvery { configurationService.getValue(searchConfigId) } returns json.encodeToJsonElement(SearchTransformConfiguration(
            expressions = SearchTransformExpressions(
                profile = "{\"new_name\": profile.name, \"is_org\": profile.type = 'organization'}"
            )
        ))

        val context = IndexStorageSystem(UUID.random(), "Admin Search Index")
        val result = transformer.transform(context, profile) as JsonObject

        assertNotNull(result["new_name"])
        assertEquals("Test Profile", (result["new_name"] as JsonPrimitive).content)
        assertEquals(false, (result["is_org"] as JsonPrimitive).boolean)
    }
}
