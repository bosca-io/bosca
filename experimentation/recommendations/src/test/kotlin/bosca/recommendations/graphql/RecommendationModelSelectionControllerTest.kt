package bosca.recommendations.graphql

import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.experimentation.model.*
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.recommendations.model.*
import bosca.recommendations.service.RecommendationStrategyService
import bosca.security.model.Principal
import bosca.security.service.*
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class RecommendationModelSelectionControllerTest {
    private val strategies = mockk<RecommendationStrategyService>()
    private val flags = mockk<FeatureFlagService>()
    private val experiments = mockk<ExperimentService>()
    private val controller = RecommendationModelSelectionController(
        strategies, flags, experiments, GroupEvaluator(mockk()), ArtifactPermissionEvaluator(mockk()),
    )
    private fun token(scope: String) = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(Principal(), emptyList(), listOf(scope), null, 1)
    }
    private fun strategy(version: String, status: RecommendationStrategyStatus = RecommendationStrategyStatus.ACTIVE) =
        RecommendationStrategy(name = "Model", type = RecommendationStrategyType.PERSONALIZED, status = status,
            configuration = Json.parseToJsonElement("""{"modelVersion":$version}"""))

    @Test
    fun `pull token without admin reads pins and live experiment versions`() = runTest {
        coEvery { strategies.getAll(0, 100) } returns listOf(strategy("7"), strategy("99", RecommendationStrategyStatus.DRAFT))
        val flag = FeatureFlag(id = UUID.random(), key = "recommendation-model", name = "Model",
            status = FlagStatus.ENABLED, defaultVariationKey = "champion",
            variations = Json.parseToJsonElement("""[{"value":5},{"value":8.0},{"value":true},{"value":2.5},{"value":"3"},{"value":0}]"""))
        coEvery { flags.getByKey("recommendation-model") } returns flag
        coEvery { experiments.getByFlagId(flag.id) } returns listOf(Experiment(
            featureFlagId = flag.id, name = "Online", controlVariationKey = "champion", status = ExperimentStatus.RUNNING))
        assertEquals(listOf(5L, 7L, 8L), controller.personalizedVersions(token("artifacts:ml:model/*:*:pull")))
        assertEquals(listOf(7L), controller.personalizedVersions(token("artifacts:ml:model/recommender-personalized:7:pull")))
        for (scope in listOf("analytics:execute", "artifacts:ml:other/*:*:pull", "artifacts:ml:model/*:*:push")) {
            assertEquals(emptyList(), controller.personalizedVersions(token(scope)))
        }
    }

    @Test
    fun `all strategy pages are read and inactive experiments ignored`() = runTest {
        coEvery { strategies.getAll(0, 100) } returns List(100) { strategy("1") }
        coEvery { strategies.getAll(100, 100) } returns listOf(strategy("9"), strategy("-1"), strategy("1e100"))
        coEvery { flags.getByKey("recommendation-model") } returns null
        assertEquals(listOf(1L, 9L), controller.personalizedVersions(token("artifacts:pull")))
        coVerify(exactly = 0) { experiments.getByFlagId(any()) }
    }

    @Test
    fun `disabled and dormant flags do not select variations`() = runTest {
        coEvery { strategies.getAll(0, 100) } returns emptyList()
        val flag = FeatureFlag(id = UUID.random(), key = "recommendation-model", name = "Model",
            defaultVariationKey = "a", variations = Json.parseToJsonElement("""[{"value":5}]"""))
        coEvery { flags.getByKey(any()) } returns flag.copy(status = FlagStatus.DISABLED)
        assertEquals(emptyList(), controller.personalizedVersions(token("artifacts:pull")))
        coVerify(exactly = 0) { experiments.getByFlagId(any()) }
        coEvery { flags.getByKey(any()) } returns flag.copy(status = FlagStatus.ENABLED)
        coEvery { experiments.getByFlagId(flag.id) } returns emptyList()
        assertEquals(emptyList(), controller.personalizedVersions(token("artifacts:pull")))
    }

    @Test
    fun `anonymous callers fail before configuration is read`() = runTest {
        val anonymous = object : AuthenticationContext(null, null) { override fun principal() = null }
        assertFailsWith<bosca.security.service.SecurityException> { controller.personalizedVersions(anonymous) }
        coVerify(exactly = 0) { strategies.getAll(any(), any()) }
    }

    @Test
    fun `selection failures propagate`() = runTest {
        coEvery { strategies.getAll(any(), any()) } throws IllegalStateException("unavailable")
        assertFailsWith<IllegalStateException> { controller.personalizedVersions(token("artifacts:pull")) }
    }
}
