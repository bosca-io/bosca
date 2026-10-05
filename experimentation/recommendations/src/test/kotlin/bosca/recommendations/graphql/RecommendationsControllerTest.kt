@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.experimentation.model.FlagEvaluation
import bosca.experimentation.service.FeatureFlagService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.recommendations.model.Recommendation
import bosca.recommendations.service.RecommendationService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

class RecommendationsControllerTest {

    private val recommendationService = mockk<RecommendationService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    // No recommendation-engine flag in these tests: evaluate throws, so resolveMlEnabled defaults to the
    // ML path and getForProfile is invoked with mlEnabled = true (the production default).
    private val featureFlagService = mockk<FeatureFlagService> {
        coEvery { evaluate(any(), any(), any(), any()) } throws IllegalStateException("flag not configured")
    }
    private val metadataService = mockk<MetadataService>()
    private val collectionService = mockk<CollectionService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val controller = RecommendationsController(
        recommendationService,
        profileService,
        groupEvaluator,
        featureFlagService,
        metadataService,
        collectionService,
        metadataPermissionEvaluator,
        collectionPermissionEvaluator,
    )

    private fun serverCall(installationId: String? = null): ServerCall {
        val request = mockk<ServerRequest>()
        every { request.header("X-Installation-ID") } returns installationId
        val call = mockk<ServerCall>()
        every { call.request } returns request
        return call
    }

    private val call = serverCall()

    init {
        coEvery { metadataService.getByIds(any()) } answers {
            firstArg<List<UUID>>().map(::metadata)
        }
        coEvery {
            metadataPermissionEvaluator.filterAllowed(
                any(),
                any<List<Metadata>>(),
                PermissionAction.VIEW,
            )
        } answers { secondArg() }
    }

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "Recommendation",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        recommendable = true,
    )

    private fun authenticatedContext(principalId: UUID = UUID.random()): AuthenticationContext {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.id } returns principalId
        every { principal.asPrincipal() } returns mockk<Principal>()
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns principal
        return auth
    }

    private fun profileOwnedBy(profileId: UUID, principalId: UUID): Profile {
        return Profile(id = profileId, type = ProfileType.GENERIC, principal = principalId, name = "Test", visibility = ProfileVisibility.PUBLIC)
    }

    // --- profile ownership ---

    @Test
    fun `missing or blank installation identity uses the active model without experiment evaluation`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, principalId)
        for (identity in listOf(null, "", "   ")) {
            controller.forYou(auth, serverCall(identity), profileId, 0, 10)
        }
        coVerify(exactly = 3) { recommendationService.getForProfile(profileId, 0, 10, true, null) }
        coVerify(exactly = 0) { featureFlagService.evaluate(any(), any(), any(), any()) }
    }


    @Test
    fun `similar filters results that have no content identity`() = runTest {
        val metadataId = UUID.random()
        coEvery { recommendationService.getSimilar(metadataId, 10) } returns listOf(
            bosca.recommendations.model.Recommendation(strategyId = UUID.NIL),
        )
        assertEquals(emptyList(), controller.similar(null, metadataId, 10))
    }

    @Test
    fun `profile allows access when principal owns profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, principalId)
        coEvery { recommendationService.getForProfile(profileId, 0, 10) } returns emptyList()

        controller.forYou(auth, call, profileId, 0, 10)

        coVerify { recommendationService.getForProfile(profileId, 0, 10) }
    }

    @Test
    fun `profile rejects access when principal does not own profile`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, otherPrincipalId)

        assertFailsWith<SecurityException> {
            controller.forYou(auth, call, profileId, 0, 10)
        }
    }

    @Test
    fun `profile allows admin to access any profile`() = runTest {
        val profileId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { recommendationService.getForProfile(profileId, 0, 10) } returns emptyList()

        controller.forYou(auth, call, profileId, 0, 10)

        coVerify { recommendationService.getForProfile(profileId, 0, 10) }
    }

    @Test
    fun `profile defaults to the authenticated user's primary profile when profileId is omitted`() = runTest {
        val principalId = UUID.random()
        val primaryProfileId = UUID.random()
        val auth = authenticatedContext(principalId)
        coEvery { profileService.getPrimaryProfile(any()) } returns profileOwnedBy(primaryProfileId, principalId)
        coEvery { recommendationService.getForProfile(primaryProfileId, 0, 10) } returns emptyList()

        controller.forYou(auth, call, null, 0, 10)

        coVerify { recommendationService.getForProfile(primaryProfileId, 0, 10) }
    }

    @Test
    fun `profile returns empty when profileId omitted and the user has no primary profile`() = runTest {
        val auth = authenticatedContext()
        coEvery { profileService.getPrimaryProfile(any()) } returns null

        val result = controller.forYou(auth, call, null, 0, 10)

        assertEquals(0, result.size)
        coVerify(exactly = 0) { recommendationService.getForProfile(any(), any(), any()) }
    }

    // --- profile coercion ---

    @Test
    fun `profile delegates to service with coerced offset and limit`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        val recommendations = listOf(
            Recommendation(metadataId = UUID.random(), strategyId = UUID.random(), score = 0.9)
        )
        coEvery { recommendationService.getForProfile(profileId, 0, 10) } returns recommendations

        val result = controller.forYou(auth, call, profileId, 0, 10)

        assertEquals(1, result.size)
        coVerify { recommendationService.getForProfile(profileId, 0, 10) }
    }

    @Test
    fun `profile filters out metadata the caller cannot view`() = runTest {
        val profileId = UUID.random()
        val allowedId = UUID.random()
        val deniedId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        val allowedMetadata = metadata(allowedId)
        val deniedMetadata = metadata(deniedId)
        coEvery { recommendationService.getForProfile(profileId, 0, 10) } returns listOf(
            Recommendation(metadataId = allowedId, strategyId = UUID.random(), score = 0.9),
            Recommendation(metadataId = deniedId, strategyId = UUID.random(), score = 0.8),
        )
        coEvery { metadataService.getByIds(listOf(allowedId, deniedId)) } returns
            listOf(allowedMetadata, deniedMetadata)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(
                auth,
                listOf(allowedMetadata, deniedMetadata),
                PermissionAction.VIEW,
            )
        } returns listOf(allowedMetadata)

        val result = controller.forYou(auth, call, profileId, 0, 10)

        assertEquals(listOf(allowedId), result.map { it.metadataId })
    }

    @Test
    fun `profile coerces negative offset to zero`() = runTest {
        val profileId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { recommendationService.getForProfile(profileId, 0, 10) } returns emptyList()

        controller.forYou(auth, call, profileId, -5, 10)

        coVerify { recommendationService.getForProfile(profileId, 0, 10) }
    }

    @Test
    fun `profile coerces limit below 1 to 1`() = runTest {
        val profileId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { recommendationService.getForProfile(profileId, 0, 1) } returns emptyList()

        controller.forYou(auth, call, profileId, 0, 0)

        coVerify { recommendationService.getForProfile(profileId, 0, 1) }
    }

    @Test
    fun `profile coerces limit above 100 to 100`() = runTest {
        val profileId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { recommendationService.getForProfile(profileId, 0, 100) } returns emptyList()

        controller.forYou(auth, call, profileId, 0, 200)

        coVerify { recommendationService.getForProfile(profileId, 0, 100) }
    }

    @Test
    fun `recommendation surfaces pass an explicit context type`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { recommendationService.getForProfile(profileId, 0, 10, true, null, "images") } returns emptyList()
        coEvery { recommendationService.getSimilar(metadataId, 10, "images") } returns emptyList()
        coEvery {
            recommendationService.getCoEngaged(metadataId, null, 10, true, null, "images")
        } returns emptyList()
        coEvery {
            recommendationService.getRecommended(metadataId, null, 10, true, null, "images")
        } returns emptyList()

        controller.forYou(auth, call, profileId, 0, 10, "images")
        controller.similar(null, metadataId, 10, "images")
        controller.coEngaged(null, call, metadataId, null, 10, "images")
        controller.recommended(null, call, metadataId, profileId, 10, "images")

        coVerify { recommendationService.getForProfile(profileId, 0, 10, true, null, "images") }
        coVerify { recommendationService.getSimilar(metadataId, 10, "images") }
        coVerify { recommendationService.getCoEngaged(metadataId, null, 10, true, null, "images") }
        coVerify { recommendationService.getRecommended(metadataId, null, 10, true, null, "images") }
    }

    @Test
    fun `recommendation surfaces pass the requested language`() = runTest {
        val metadataId = UUID.random()
        coEvery { recommendationService.getSimilar(metadataId, 10, "images", "fr") } returns emptyList()

        controller.similar(null, metadataId, 10, "images", "fr")

        coVerify { recommendationService.getSimilar(metadataId, 10, "images", "fr") }
    }

    // --- placement ownership ---

    @Test
    fun `placement verifies ownership when profileId is provided`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val otherPrincipalId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, otherPrincipalId)

        assertFailsWith<SecurityException> {
            controller.placement(auth, profileId, "home_feed", 5)
        }
    }

    @Test
    fun `placement allows null profileId for anonymous users`() = runTest {
        coEvery { recommendationService.getForPlacement(null, "home_feed", 10) } returns emptyList()

        val result = controller.placement(null, null, "home_feed", 10)

        assertEquals(0, result.size)
        coVerify { recommendationService.getForPlacement(null, "home_feed", 10) }
    }

    @Test
    fun `placement passes correct args to service`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        val recommendations = listOf(
            Recommendation(metadataId = UUID.random(), strategyId = UUID.random(), score = 0.8)
        )
        coEvery { recommendationService.getForPlacement(profileId, "home_feed", 5) } returns recommendations

        val result = controller.placement(auth, profileId, "home_feed", 5)

        assertEquals(1, result.size)
        coVerify { recommendationService.getForPlacement(profileId, "home_feed", 5) }
    }

    @Test
    fun `placement passes an explicit recommendation context type to the service`() = runTest {
        coEvery { recommendationService.getForPlacement(null, "video-picker", 5, "videos") } returns emptyList()

        controller.placement(null, null, "video-picker", 5, "videos")

        coVerify { recommendationService.getForPlacement(null, "video-picker", 5, "videos") }
    }

    @Test
    fun `placement coerces limit above 50 to 50`() = runTest {
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { recommendationService.getForPlacement(any(), any(), eq(50)) } returns emptyList()

        controller.placement(auth, UUID.random(), "sidebar", 100)

        coVerify { recommendationService.getForPlacement(any(), eq("sidebar"), eq(50)) }
    }

    // --- trending ---

    @Test
    fun `trending passes correct args to service`() = runTest {
        val recommendations = listOf(
            Recommendation(metadataId = UUID.random(), strategyId = UUID.random(), score = 0.95)
        )
        coEvery { recommendationService.getTrending(0, 20) } returns recommendations

        val result = controller.trending(null, 0, 20)

        assertEquals(1, result.size)
        coVerify { recommendationService.getTrending(0, 20) }
    }

    @Test
    fun `trending coerces negative offset to zero`() = runTest {
        coEvery { recommendationService.getTrending(0, 10) } returns emptyList()

        controller.trending(null, -10, 10)

        coVerify { recommendationService.getTrending(0, 10) }
    }

    @Test
    fun `trending coerces limit above 100 to 100`() = runTest {
        coEvery { recommendationService.getTrending(0, 100) } returns emptyList()

        controller.trending(null, 0, 500)

        coVerify { recommendationService.getTrending(0, 100) }
    }

    @Test
    fun `trending passes an explicit recommendation context type to the service`() = runTest {
        coEvery { recommendationService.getTrending(0, 10, "images") } returns emptyList()

        controller.trending(null, 0, 10, "images")

        coVerify { recommendationService.getTrending(0, 10, "images") }
    }

    @Test
    fun `trending filters out collections the caller cannot view`() = runTest {
        val allowedId = UUID.random()
        val deniedId = UUID.random()
        val auth = authenticatedContext()
        val allowedCollection = Collection(
            id = allowedId,
            name = "Allowed",
            languageTag = "en",
            workflowStateId = "published",
        )
        val deniedCollection = Collection(
            id = deniedId,
            name = "Denied",
            languageTag = "en",
            workflowStateId = "published",
        )
        coEvery { recommendationService.getTrending(0, 10) } returns listOf(
            Recommendation(
                collectionId = allowedId,
                strategyId = UUID.random(),
                score = 0.9,
            ),
            Recommendation(
                collectionId = deniedId,
                strategyId = UUID.random(),
                score = 0.8,
            ),
        )
        coEvery { collectionService.getByIds(listOf(allowedId, deniedId)) } returns
            listOf(allowedCollection, deniedCollection)
        coEvery {
            collectionPermissionEvaluator.filterAllowed(
                auth,
                listOf(allowedCollection, deniedCollection),
                PermissionAction.VIEW,
            )
        } returns listOf(allowedCollection)

        val result = controller.trending(auth, 0, 10)

        assertEquals(listOf(allowedId), result.map { it.collectionId })
    }

    // --- similar ---

    @Test
    fun `similar passes correct args to service`() = runTest {
        val metadataId = UUID.random()
        val recommendations = listOf(
            Recommendation(metadataId = UUID.random(), strategyId = UUID.random(), score = 0.7)
        )
        coEvery { recommendationService.getSimilar(metadataId, 5) } returns recommendations

        val result = controller.similar(null, metadataId, 5)

        assertEquals(1, result.size)
        coVerify { recommendationService.getSimilar(metadataId, 5) }
    }

    @Test
    fun `similar coerces limit above 50 to 50`() = runTest {
        val metadataId = UUID.random()
        coEvery { recommendationService.getSimilar(metadataId, 50) } returns emptyList()

        controller.similar(null, metadataId, 100)

        coVerify { recommendationService.getSimilar(metadataId, 50) }
    }

    // --- coEngaged / recommended + A/B engine resolution ---

    private fun flagEval(flagKey: String, variationKey: String, value: kotlinx.serialization.json.JsonElement, experimentId: UUID? = null) =
        FlagEvaluation(flagKey = flagKey, variationKey = variationKey, value = value, experimentId = experimentId)

    @Test
    fun `coEngaged for a viewer resolves the ml arm and pins the experiment's model version`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { featureFlagService.evaluate("recommendation-engine", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-engine", "ml", kotlinx.serialization.json.JsonPrimitive("ml"))
        coEvery { featureFlagService.evaluate("recommendation-model", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-model", "challenger", kotlinx.serialization.json.JsonPrimitive(5L), experimentId = UUID.random())
        coEvery { recommendationService.getCoEngaged(metadataId, profileId, 10, true, 5L) } returns emptyList()

        controller.coEngaged(auth, serverCall(INSTALLATION_ID), metadataId, profileId, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, profileId, 10, true, 5L) }
    }

    @Test
    fun `coEngaged on the heuristic arm bypasses the ml ranker`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { featureFlagService.evaluate("recommendation-engine", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-engine", "heuristic", kotlinx.serialization.json.JsonPrimitive("heuristic"))
        coEvery { recommendationService.getCoEngaged(metadataId, profileId, 10, false, null) } returns emptyList()

        controller.coEngaged(auth, serverCall(INSTALLATION_ID), metadataId, profileId, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, profileId, 10, false, null) }
    }

    @Test
    fun `coEngaged for an anonymous viewer serves the global set without evaluating flags`() = runTest {
        val metadataId = UUID.random()
        coEvery { recommendationService.getCoEngaged(metadataId, null, 10, true, null) } returns emptyList()

        controller.coEngaged(null, call, metadataId, null, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, null, 10, true, null) }
        coVerify(exactly = 0) { featureFlagService.evaluate(any(), any(), any(), any()) }
    }

    @Test
    fun `recommended for a viewer resolves the engine and delegates`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        // Flag evaluation throws (setup default) → resolveEngine degrades to the ML default, model unpinned.
        coEvery { recommendationService.getRecommended(metadataId, profileId, 10, true, null) } returns emptyList()

        controller.recommended(auth, serverCall(INSTALLATION_ID), metadataId, profileId, 10)

        coVerify { recommendationService.getRecommended(metadataId, profileId, 10, true, null) }
    }

    @Test
    fun `recommended for an anonymous viewer serves the global merge`() = runTest {
        val metadataId = UUID.random()
        coEvery { recommendationService.getRecommended(metadataId, null, 10, true, null) } returns emptyList()

        controller.recommended(null, call, metadataId, null, 10)

        coVerify { recommendationService.getRecommended(metadataId, null, 10, true, null) }
    }

    @Test
    fun `strategies and placements expose their sub-controllers`() {
        assertEquals(RecommendationStrategies, controller.strategies())
        assertEquals(RecommendationPlacements, controller.placements())
        assertEquals(RecommendationContexts, controller.contexts())
        assertEquals(PersonalizationSignals, controller.personalizationSignals())
    }

    @Test
    fun `related ignores an explicit profileId without authentication`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        coEvery { recommendationService.getCoEngaged(metadataId, null, 10, true, null) } returns emptyList()

        controller.coEngaged(null, call, metadataId, profileId, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, null, 10, true, null) }
        coVerify(exactly = 0) { featureFlagService.evaluate(any(), any(), any(), any()) }
    }

    @Test
    fun `related ignores an explicit profileId when the principal is unavailable`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns null
        coEvery { recommendationService.getCoEngaged(metadataId, null, 10, true, null) } returns emptyList()

        controller.coEngaged(auth, call, metadataId, profileId, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, null, 10, true, null) }
        coVerify(exactly = 0) { featureFlagService.evaluate(any(), any(), any(), any()) }
    }

    @Test
    fun `resolveEngine leaves the model version unpinned when the model flag is not an experiment`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { featureFlagService.evaluate("recommendation-engine", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-engine", "ml", kotlinx.serialization.json.JsonPrimitive("ml"))
        // No experimentId → the model variation is a default, not a live assignment → version stays unpinned.
        coEvery { featureFlagService.evaluate("recommendation-model", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-model", "champion", kotlinx.serialization.json.JsonPrimitive(5L), experimentId = null)
        coEvery { recommendationService.getCoEngaged(metadataId, profileId, 10, true, null) } returns emptyList()

        controller.coEngaged(auth, serverCall(INSTALLATION_ID), metadataId, profileId, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, profileId, 10, true, null) }
    }

    @Test
    fun `resolveEngine leaves the model version unpinned when the flag value is not a primitive`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { featureFlagService.evaluate("recommendation-engine", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-engine", "ml", kotlinx.serialization.json.JsonPrimitive("ml"))
        coEvery { featureFlagService.evaluate("recommendation-model", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-model", "challenger", kotlinx.serialization.json.JsonObject(emptyMap()), experimentId = UUID.random())
        coEvery { recommendationService.getCoEngaged(metadataId, profileId, 10, true, null) } returns emptyList()

        controller.coEngaged(auth, serverCall(INSTALLATION_ID), metadataId, profileId, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, profileId, 10, true, null) }
    }

    @Test
    fun `related omitting profileId with an authenticated principal-less context serves the global set`() = runTest {
        val metadataId = UUID.random()
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns null // authenticated context but no principal → no profile resolved
        coEvery { recommendationService.getCoEngaged(metadataId, null, 10, true, null) } returns emptyList()

        controller.coEngaged(auth, call, metadataId, null, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, null, 10, true, null) }
    }

    @Test
    fun `resolveEngine leaves the model version unpinned when the flag value is not a number`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { featureFlagService.evaluate("recommendation-engine", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-engine", "ml", kotlinx.serialization.json.JsonPrimitive("ml"))
        coEvery { featureFlagService.evaluate("recommendation-model", principalId, INSTALLATION_ID, null) } returns
            flagEval("recommendation-model", "challenger", kotlinx.serialization.json.JsonPrimitive("not-a-number"), experimentId = UUID.random())
        coEvery { recommendationService.getCoEngaged(metadataId, profileId, 10, true, null) } returns emptyList()

        controller.coEngaged(auth, serverCall(INSTALLATION_ID), metadataId, profileId, 10)

        coVerify { recommendationService.getCoEngaged(metadataId, profileId, 10, true, null) }
    }

    private companion object {
        const val INSTALLATION_ID = "installation-1"
    }
}
