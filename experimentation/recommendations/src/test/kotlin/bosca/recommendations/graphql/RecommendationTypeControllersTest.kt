@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.recommendations.model.RecommendationPlacement
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.service.RecommendationPlacementService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Field-resolver tests for the RecommendationStrategy and RecommendationPlacement GraphQL type
 * controllers: the scalar pass-throughs plus the admin-gated resolved fields (a strategy's
 * evaluation schedule from its scheduler job, and a placement's linked strategies).
 */
class RecommendationTypeControllersTest {

    private val strategyService = mockk<RecommendationStrategyService>(relaxed = true)
    private val placementService = mockk<RecommendationPlacementService>(relaxed = true)
    private val schedulerService = mockk<SchedulerService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    // ── RecommendationStrategyController ────────────────────────────────────────────────────────

    private val strategyController = RecommendationStrategyController(schedulerService, groupEvaluator)

    private fun strategy() = RecommendationStrategy(
        id = UUID.random(),
        name = "S",
        description = "d",
        type = RecommendationStrategyType.TRENDING,
        status = RecommendationStrategyStatus.ACTIVE,
        analyticsQueryId = UUID.random(),
        configuration = JsonPrimitive("c"),
        priority = 2,
        maxRecommendations = 15,
        scheduledJobId = UUID.random(),
        lastEvaluated = OffsetDateTime.now(),
    )

    @Test
    fun `strategy scalar fields pass through from the model`() {
        val s = strategy()
        assertEquals(s.id, strategyController.id(s))
        assertEquals("S", strategyController.name(s))
        assertEquals("d", strategyController.description(s))
        assertEquals(RecommendationStrategyType.TRENDING, strategyController.type(s))
        assertEquals(RecommendationStrategyStatus.ACTIVE, strategyController.status(s))
        assertEquals(s.analyticsQueryId, strategyController.analyticsQueryId(s))
        assertEquals(s.configuration, strategyController.configuration(s))
        assertEquals(2, strategyController.priority(s))
        assertEquals(15, strategyController.maxRecommendations(s))
        assertEquals(s.scheduledJobId, strategyController.scheduledJobId(s))
        assertEquals(s.lastEvaluated, strategyController.lastEvaluated(s))
        assertEquals(s.created, strategyController.created(s))
        assertEquals(s.modified, strategyController.modified(s))
    }

    @Test
    fun `evaluationSchedule resolves the cron from the linked scheduler job for an admin`() = runTest {
        val s = strategy()
        coEvery { schedulerService.getJob(s.scheduledJobId!!) } returns
            mockk<ScheduledJob> { every { cronExpression } returns "0 * * * *" }

        val result = strategyController.evaluationSchedule(auth, s)

        assertEquals("0 * * * *", result)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `evaluationSchedule is null when the strategy has no scheduled job`() = runTest {
        val s = strategy().copy(scheduledJobId = null)
        assertNull(strategyController.evaluationSchedule(auth, s))
    }

    @Test
    fun `evaluationSchedule is null when the scheduled job no longer exists`() = runTest {
        val s = strategy()
        coEvery { schedulerService.getJob(s.scheduledJobId!!) } returns null // job id set but job gone
        assertNull(strategyController.evaluationSchedule(auth, s))
    }

    // ── RecommendationPlacementController ───────────────────────────────────────────────────────

    private val placementController = RecommendationPlacementController(placementService, strategyService, groupEvaluator)

    private fun placement() = RecommendationPlacement(
        id = UUID.random(),
        name = "P",
        description = "pd",
        slug = "home",
        maxItems = 8,
        configuration = JsonPrimitive("pc"),
    )

    @Test
    fun `placement scalar fields pass through from the model`() {
        val p = placement()
        assertEquals(p.id, placementController.id(p))
        assertEquals("P", placementController.name(p))
        assertEquals("pd", placementController.description(p))
        assertEquals("home", placementController.slug(p))
        assertEquals(8, placementController.maxItems(p))
        assertEquals(p.configuration, placementController.configuration(p))
        assertEquals(p.created, placementController.created(p))
        assertEquals(p.modified, placementController.modified(p))
    }

    @Test
    fun `placement strategies resolves the linked strategies for an admin`() = runTest {
        val p = placement()
        val ids = listOf(UUID.random(), UUID.random())
        coEvery { placementService.getStrategyIds(p.id) } returns ids
        coEvery { strategyService.getByIds(ids) } returns listOf(strategy())

        val result = placementController.strategies(auth, p)

        assertEquals(1, result.size)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { strategyService.getByIds(ids) }
    }

    // ── EngineExperimentProvisioningController ──────────────────────────────────────────────────

    @Test
    fun `provisioning result fields pass through`() {
        val controller = EngineExperimentProvisioningController()
        val p = bosca.recommendations.model.EngineExperimentProvisioning(
            experimentId = UUID.random(), flagKey = "recommendation-engine", created = true,
        )
        assertEquals(p.experimentId, controller.experimentId(p))
        assertEquals("recommendation-engine", controller.flagKey(p))
        assertEquals(true, controller.created(p))
    }
}
