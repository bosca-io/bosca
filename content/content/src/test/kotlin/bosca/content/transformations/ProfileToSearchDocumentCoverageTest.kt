package bosca.content.transformations

import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationMember
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchTransformConfiguration
import bosca.search.model.SearchTransformExpressions
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileToSearchDocumentCoverageTest {

    private val profileService = mockk<ProfileService>()
    private val organizationService = mockk<OrganizationService>()
    private val slugs = mockk<SlugService>()
    // encodeDefaults = true so default-valued fields (e.g. ProfileSearchContext.memberCount = 0)
    // are actually written to the encoded JSON; otherwise result["memberCount"] would be absent
    // and the `as JsonPrimitive` cast in the assertions would NPE.
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val configurationService = mockk<ConfigurationService>()

    private val profileId = UUID.random()
    private val principalId = UUID.random()
    private val orgId = UUID.random()
    private val searchConfigId = UUID.random()

    private fun transformer(configuration: ProfileToSearchDocumentConfiguration) =
        ProfileToSearchDocument(
            profileService,
            organizationService,
            slugs,
            json,
            configuration,
            configurationService
        )

    private fun genericProfile(principal: UUID? = null) = Profile(
        id = profileId,
        type = ProfileType.GENERIC,
        principal = principal,
        collectionId = null,
        name = "Generic Profile",
        visibility = ProfileVisibility.PUBLIC,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    private fun organizationProfile(principal: UUID? = null) = Profile(
        id = profileId,
        type = ProfileType.ORGANIZATION,
        principal = principal,
        collectionId = null,
        name = "Org Profile",
        visibility = ProfileVisibility.PUBLIC,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    private fun organization(id: UUID = orgId) = Organization(
        id = id,
        name = "Acme",
        attributes = JsonObject(emptyMap()),
        systemAttributes = JsonObject(emptyMap()),
        visibility = ProfileVisibility.PUBLIC,
        profileId = profileId,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    // Non-admin, GENERIC, no principal, empty exclude config, no search config -> encoded context.
    // Covers: name != admin (true), type == null, GENERIC contentType, empty prefix .any (false),
    // isOrganization false, organization null, principal null -> organizations emptyList,
    // memberCount 0L, searchConfiguration null -> expression null -> encodeToJsonElement.
    @Test
    fun `non-admin generic profile with no config returns encoded context`() = runTest {
        val configuration = ProfileToSearchDocumentConfiguration()
        coEvery { profileService.getAttributes(profileId) } returns emptyList()
        coEvery { slugs.getProfileSlug(profileId) } returns "generic-slug"
        coEvery { configurationService.getByKey("search") } returns null

        val context = IndexStorageSystem(UUID.random(), "Some Public Index")
        val result = transformer(configuration).transform(context, genericProfile()) as JsonObject

        assertEquals("bosca/v-profile-generic", (result["contentType"] as JsonPrimitive).content)
        assertEquals("generic-slug", (result["slug"] as JsonPrimitive).content)
        assertEquals(0L, (result["memberCount"] as JsonPrimitive).long)
        assertTrue(result["organization"] is JsonNull)
        assertEquals(0, (result["organizations"] as JsonArray).size)
    }

    // Non-admin, ORGANIZATION with principal -> org lookups + member organizations mapping.
    // Covers: ORGANIZATION contentType, isOrganization true, getOrganizationByProfile non-null,
    // organization != null -> getMemberCount, principal != null -> getMemberOrganizations + map getOrganization.
    @Test
    fun `non-admin organization profile resolves org member count and organizations`() = runTest {
        val configuration = ProfileToSearchDocumentConfiguration()
        val org = organization()
        val otherOrgId = UUID.random()
        val otherOrg = organization(otherOrgId)
        coEvery { organizationService.getOrganizationByProfile(profileId) } returns org
        coEvery { organizationService.getMemberCount(orgId) } returns 42L
        coEvery { organizationService.getMemberOrganizations(principalId) } returns
            listOf(OrganizationMember(otherOrgId, principalId))
        coEvery { organizationService.getOrganization(otherOrgId) } returns otherOrg
        coEvery { profileService.getAttributes(profileId) } returns emptyList()
        coEvery { slugs.getProfileSlug(profileId) } returns "org-slug"
        coEvery { configurationService.getByKey("search") } returns null

        val context = IndexStorageSystem(UUID.random(), "Public Index")
        val result = transformer(configuration).transform(context, organizationProfile(principalId)) as JsonObject

        assertEquals("bosca/v-profile-organization", (result["contentType"] as JsonPrimitive).content)
        assertEquals(42L, (result["memberCount"] as JsonPrimitive).long)
        assertNotNull(result["organization"])
        assertTrue(result["organization"] !is JsonNull)
        assertEquals(1, (result["organizations"] as JsonArray).size)
    }

    // Non-admin with excludeContentTypePrefix that matches the generic content type -> null.
    // Covers: excludeContentTypePrefix.any { startsWith } == true (early return null).
    @Test
    fun `non-admin returns null when content type prefix is excluded`() = runTest {
        val configuration = ProfileToSearchDocumentConfiguration(
            excludeContentTypePrefix = listOf("bosca/v-profile")
        )
        val context = IndexStorageSystem(UUID.random(), "Public Index")
        val result = transformer(configuration).transform(context, genericProfile())

        assertNull(result)
    }

    // Non-admin with a non-matching excludeContentTypePrefix -> proceeds normally.
    // Covers: excludeContentTypePrefix non-empty but .any == false (startsWith false), and no search config.
    @Test
    fun `non-admin proceeds when content type prefix does not match`() = runTest {
        val configuration = ProfileToSearchDocumentConfiguration(
            excludeContentTypePrefix = listOf("something/else")
        )
        coEvery { profileService.getAttributes(profileId) } returns emptyList()
        coEvery { slugs.getProfileSlug(profileId) } returns null
        coEvery { configurationService.getByKey("search") } returns null

        val context = IndexStorageSystem(UUID.random(), "Public Index")
        val result = transformer(configuration).transform(context, genericProfile()) as JsonObject

        // slug null -> "" fallback via elvis
        assertEquals("", (result["slug"] as JsonPrimitive).content)
        assertEquals("bosca/v-profile-generic", (result["contentType"] as JsonPrimitive).content)
    }

    // Non-admin with a non-empty excludeTypes set; profile attributes are null so type resolves null
    // and the (type != null && type in excludeTypes) guard is false -> not excluded.
    @Test
    fun `non-admin with configured exclude types is not excluded when type is absent`() = runTest {
        val configuration = ProfileToSearchDocumentConfiguration(
            excludeTypes = setOf("robot")
        )
        coEvery { profileService.getAttributes(profileId) } returns emptyList()
        coEvery { slugs.getProfileSlug(profileId) } returns "still-here"
        coEvery { configurationService.getByKey("search") } returns null

        val context = IndexStorageSystem(UUID.random(), "Public Index")
        val result = transformer(configuration).transform(context, genericProfile()) as JsonObject

        assertEquals("still-here", (result["slug"] as JsonPrimitive).content)
    }

    // Search config present but the profile expression is blank -> isNullOrBlank true -> encoded context.
    // Covers: searchConfiguration non-null, expression blank branch of isNullOrBlank.
    @Test
    fun `blank profile expression returns encoded context without jsonata`() = runTest {
        val configuration = ProfileToSearchDocumentConfiguration()
        coEvery { profileService.getAttributes(profileId) } returns emptyList()
        coEvery { slugs.getProfileSlug(profileId) } returns "blank-expr-slug"
        coEvery { configurationService.getByKey("search") } returns Configuration(searchConfigId, "search", "", false)
        coEvery { configurationService.getValue(searchConfigId) } returns json.encodeToJsonElement(
            SearchTransformConfiguration(
                expressions = SearchTransformExpressions(profile = "   ")
            )
        )

        val context = IndexStorageSystem(UUID.random(), "Public Index")
        val result = transformer(configuration).transform(context, genericProfile()) as JsonObject

        assertEquals("blank-expr-slug", (result["slug"] as JsonPrimitive).content)
        // Unmodified context has a "profile" field; jsonata output would have differed.
        assertNotNull(result["profile"])
    }

    // Admin Search Index path skips exclusion logic entirely, and a configured expression is applied.
    // Covers: name == "Admin Search Index" (false branch of the initial guard) with expression non-blank.
    @Test
    fun `admin index applies jsonata expression and skips exclusion checks`() = runTest {
        val configuration = ProfileToSearchDocumentConfiguration(
            excludeTypes = setOf("robot"),
            excludeContentTypePrefix = listOf("bosca/v-profile")
        )
        coEvery { profileService.getAttributes(profileId) } returns emptyList()
        coEvery { slugs.getProfileSlug(profileId) } returns "admin-slug"
        coEvery { configurationService.getByKey("search") } returns Configuration(searchConfigId, "search", "", false)
        coEvery { configurationService.getValue(searchConfigId) } returns json.encodeToJsonElement(
            SearchTransformConfiguration(
                expressions = SearchTransformExpressions(profile = "{\"new_name\": profile.name}")
            )
        )

        val context = IndexStorageSystem(UUID.random(), "Admin Search Index")
        // Despite the exclude config matching the generic content type, the admin path bypasses it.
        val result = transformer(configuration).transform(context, genericProfile()) as JsonObject

        assertEquals("Generic Profile", (result["new_name"] as JsonPrimitive).content)
    }
}
