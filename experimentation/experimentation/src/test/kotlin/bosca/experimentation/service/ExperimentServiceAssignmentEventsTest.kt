@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.service

import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.events.ExperimentVariationAssigned
import bosca.experimentation.events.dispatch
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.ExperimentUpdated
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.VariationWeight
import bosca.experimentation.repository.AnalysisReportRepository
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ConversionGoalRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.ExperimentResultRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.repository.RolloutPolicyEventRepository
import bosca.observability.ErrorCapture
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.di.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that ExperimentVariationAssigned fires exactly once — on the fresh insert — and never
 * for an already-assigned user (the idempotent early return and the lost-race conflict path).
 */
class ExperimentServiceAssignmentEventsTest {

    private val experimentRepository = mockk<ExperimentRepository>()
    private val assignmentRepository = mockk<AssignmentRepository>()
    private val conversionGoalRepository = mockk<ConversionGoalRepository>()
    private val experimentResultRepository = mockk<ExperimentResultRepository>()
    private val analysisReportRepository = mockk<AnalysisReportRepository>()
    private val featureFlagRepository = mockk<FeatureFlagRepository>()
    private val rolloutPolicyEventRepository = mockk<RolloutPolicyEventRepository>()
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val errorCapture = mockk<ErrorCapture>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val cacheSupport = FlagCacheTestSupport()

    private val service by lazy {
        ExperimentServiceImpl(
            experimentRepository,
            assignmentRepository,
            conversionGoalRepository,
            experimentResultRepository,
            analysisReportRepository,
            featureFlagRepository,
            rolloutPolicyEventRepository,
            pubSubService,
            json,
            errorCapture,
        )
    }

    private val assignedEvents = mutableListOf<ExperimentVariationAssigned>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        cacheSupport.installInDi()
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Assignment>(any())
        } coAnswers {
            val block = firstArg<suspend () -> Assignment>()
            block()
        }
        mockkStatic("bosca.experimentation.events.ExperimentVariationAssignedExtKt")
        coEvery { any<ExperimentVariationAssigned>().dispatch() } coAnswers { assignedEvents += firstArg<ExperimentVariationAssigned>() }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        unmockkStatic("bosca.experimentation.events.ExperimentVariationAssignedExtKt")
        unmockkObject(ProviderRegistry)
        ProviderRegistry.clear()
    }

    private fun stubExperiment(
        experimentId: UUID,
        exclusionLayerId: UUID? = null,
        rollout: Rollout = Rollout(listOf(VariationWeight("treatment", 1))),
    ): Experiment {
        val flagId = UUID.random()
        val rule = TargetingRule(
            id = "rule-1",
            rollout = rollout,
        )
        val flag = FeatureFlag(
            id = flagId,
            key = "test-flag",
            name = "Test Flag",
            variations = JsonPrimitive("[]"),
            defaultVariationKey = "control",
            targetingRules = json.encodeToJsonElement(ListSerializer(TargetingRule.serializer()), listOf(rule)),
            salt = "salt",
        )
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = flagId,
            controlVariationKey = "control",
            name = "Test Experiment",
            targetingRuleId = "rule-1",
            exclusionLayerId = exclusionLayerId,
        )
        coEvery { experimentRepository.getById(experimentId) } returns experiment
        coEvery { featureFlagRepository.getById(flagId) } returns flag
        return experiment
    }

    private fun cacheTest(block: suspend () -> Unit) = runTest {
        cacheSupport.withFlagCache { block() }
    }

    @Test
    fun `a fresh assignment fires ExperimentVariationAssigned once`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-1"
        stubExperiment(experimentId)
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns null
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "treatment", principalId, installationId)
        } returns Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            principalId = principalId,
            installationId = installationId,
        )

        service.assignVariation(experimentId, principalId, installationId)

        assertEquals(1, assignedEvents.size)
        assertEquals(experimentId, assignedEvents.single().experimentId)
        assertEquals(principalId, assignedEvents.single().principalId)
        assertEquals(installationId, assignedEvents.single().installationId)
        assertEquals("treatment", assignedEvents.single().variationKey)
    }

    @Test
    fun `a fresh authenticated assignment buckets by installation`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.parse("00000000-0000-0000-0000-000000000002")
        val installationId = "device-login"
        stubExperiment(
            experimentId,
            rollout = Rollout(
                listOf(
                    VariationWeight("off", 50),
                    VariationWeight("on", 50),
                ),
            ),
        )
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns null
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "on", principalId, installationId)
        } returns Assignment(
            experimentId = experimentId,
            variationKey = "on",
            principalId = principalId,
            installationId = installationId,
        )

        val assignment = service.assignVariation(experimentId, principalId, installationId)

        assertEquals("on", assignment.variationKey)
        coVerify(exactly = 1) {
            assignmentRepository.addIfAbsent(experimentId, "on", principalId, installationId)
        }
    }

    @Test
    fun `an existing assignment fires no event`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-1"
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns
            Assignment(
                experimentId = experimentId,
                variationKey = "treatment",
                principalId = principalId,
                installationId = installationId,
            )

        service.assignVariation(experimentId, principalId, installationId)

        assertEquals(0, assignedEvents.size)
    }

    @Test
    fun `losing the insert race fires no event`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-1"
        stubExperiment(experimentId)
        // No assignment at the optimistic check, but the insert conflicts (another request won).
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns null andThen
            Assignment(
                experimentId = experimentId,
                variationKey = "treatment",
                principalId = principalId,
                installationId = installationId,
            )
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "treatment", principalId, installationId)
        } returns null

        service.assignVariation(experimentId, principalId, installationId)

        assertEquals(0, assignedEvents.size)
    }

    @Test
    fun `assignment lookup is cached across requests`() = runTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-cache"
        val assignment = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            principalId = principalId,
            installationId = installationId,
        )
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        } returns assignment

        cacheSupport.withFlagCache {
            assertEquals(assignment, service.getAssignment(experimentId, principalId, installationId))
        }
        cacheSupport.withFlagCache {
            assertEquals(assignment, service.getAssignment(experimentId, principalId, installationId))
        }

        coVerify(exactly = 1) {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        }
    }

    @Test
    fun `assignVariation rejects a blank installation id before repository access`() = cacheTest {
        assertFailsWith<IllegalArgumentException> {
            service.assignVariation(UUID.random(), null, " ")
        }

        coVerify(exactly = 0) { assignmentRepository.getByIdentity(any(), any(), any()) }
    }

    @Test
    fun `assignVariation fails when the experiment no longer exists`() = cacheTest {
        val experimentId = UUID.random()
        coEvery { assignmentRepository.getByIdentity(experimentId, null, "installation") } returns null
        coEvery { experimentRepository.getById(experimentId) } returns null

        assertFailsWith<IllegalStateException> {
            service.assignVariation(experimentId, null, "installation")
        }
    }

    @Test
    fun `assignVariation fails when the experiment flag no longer exists`() = cacheTest {
        val experimentId = UUID.random()
        val experiment = stubExperiment(experimentId)
        coEvery { assignmentRepository.getByIdentity(experimentId, null, "installation") } returns null
        coEvery { featureFlagRepository.getById(experiment.featureFlagId) } returns null

        assertFailsWith<IllegalStateException> {
            service.assignVariation(experimentId, null, "installation")
        }
    }

    @Test
    fun `assignVariation fails when the attached targeting rule no longer exists`() = cacheTest {
        val experimentId = UUID.random()
        val experiment = stubExperiment(experimentId)
        coEvery { assignmentRepository.getByIdentity(experimentId, null, "installation") } returns null
        coEvery {
            featureFlagRepository.getById(experiment.featureFlagId)
        } returns FeatureFlag(
            id = experiment.featureFlagId,
            key = "test-flag",
            name = "Test Flag",
            variations = JsonPrimitive("[]"),
            defaultVariationKey = "control",
            targetingRules = null,
            salt = "salt",
        )

        assertFailsWith<IllegalStateException> {
            service.assignVariation(experimentId, null, "installation")
        }
    }

    @Test
    fun `fresh authenticated assignment warms anonymous identity alias`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-alias"
        stubExperiment(experimentId)
        val assignment = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            principalId = principalId,
            installationId = installationId,
        )
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns null
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "treatment", principalId, installationId)
        } returns assignment

        assertEquals(assignment, service.assignVariation(experimentId, principalId, installationId))
        assertEquals(assignment, service.getAssignment(experimentId, null, installationId))

        coVerify(exactly = 1) {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        }
        coVerify(exactly = 0) {
            assignmentRepository.getByIdentity(experimentId, null, installationId)
        }
    }

    @Test
    fun `existing anonymous assignment is claimed and both cache aliases are refreshed`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-claim"
        val anonymous = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            installationId = installationId,
        )
        val claimed = anonymous.copy(principalId = principalId)
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns anonymous
        coEvery { assignmentRepository.claimPrincipal(experimentId, installationId, principalId) } returns claimed

        assertEquals(claimed, service.assignVariation(experimentId, principalId, installationId))
        assertEquals(claimed, service.getAssignment(experimentId, null, installationId))
        assertEquals(claimed, service.getAssignment(experimentId, principalId, installationId))

        coVerify(exactly = 1) {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        }
        coVerify(exactly = 1) {
            assignmentRepository.claimPrincipal(experimentId, installationId, principalId)
        }
    }

    @Test
    fun `claim race reloads the assignment when another request wins`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-claim-race"
        val anonymous = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            installationId = installationId,
        )
        val claimed = anonymous.copy(principalId = principalId)
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        } returns anonymous andThen claimed
        coEvery { assignmentRepository.claimPrincipal(experimentId, installationId, principalId) } returns null

        assertEquals(claimed, service.assignVariation(experimentId, principalId, installationId))

        coVerify(exactly = 2) {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        }
    }

    @Test
    fun `claim race fails loudly when the assignment disappears`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-claim-missing"
        val anonymous = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            installationId = installationId,
        )
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        } returns anonymous andThen null
        coEvery { assignmentRepository.claimPrincipal(experimentId, installationId, principalId) } returns null

        assertFailsWith<IllegalStateException> {
            service.assignVariation(experimentId, principalId, installationId)
        }
    }

    @Test
    fun `owned installation cannot be transferred to another principal`() = cacheTest {
        val experimentId = UUID.random()
        val ownerId = UUID.random()
        val otherPrincipalId = UUID.random()
        val installationId = "installation-owned"
        coEvery {
            assignmentRepository.getByIdentity(experimentId, otherPrincipalId, installationId)
        } returns Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            principalId = ownerId,
            installationId = installationId,
        )

        assertFailsWith<IllegalStateException> {
            service.assignVariation(experimentId, otherPrincipalId, installationId)
        }

        coVerify(exactly = 0) {
            assignmentRepository.claimPrincipal(experimentId, installationId, otherPrincipalId)
        }
    }

    @Test
    fun `principal assignment from another installation is reused without transferring ownership`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val canonicalInstallationId = "installation-original"
        val requestedInstallationId = "installation-new"
        val assignment = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            principalId = principalId,
            installationId = canonicalInstallationId,
        )
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, requestedInstallationId)
        } returns assignment

        assertEquals(assignment, service.assignVariation(experimentId, principalId, requestedInstallationId))

        coVerify(exactly = 0) {
            assignmentRepository.claimPrincipal(experimentId, requestedInstallationId, principalId)
        }
    }

    @Test
    fun `lost insert race claims an anonymous winner without emitting a duplicate event`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-insert-race"
        stubExperiment(experimentId)
        val anonymous = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            installationId = installationId,
        )
        val claimed = anonymous.copy(principalId = principalId)
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        } returns null andThen anonymous
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "treatment", principalId, installationId)
        } returns null
        coEvery { assignmentRepository.claimPrincipal(experimentId, installationId, principalId) } returns claimed

        assertEquals(claimed, service.assignVariation(experimentId, principalId, installationId))
        assertEquals(0, assignedEvents.size)
    }

    @Test
    fun `lost insert and claim races reload the authenticated winner`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-double-race"
        stubExperiment(experimentId)
        val anonymous = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            installationId = installationId,
        )
        val claimed = anonymous.copy(principalId = principalId)
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        } returns null andThen anonymous andThen claimed
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "treatment", principalId, installationId)
        } returns null
        coEvery { assignmentRepository.claimPrincipal(experimentId, installationId, principalId) } returns null

        assertEquals(claimed, service.assignVariation(experimentId, principalId, installationId))
        assertEquals(0, assignedEvents.size)
    }

    @Test
    fun `lost insert and claim races fail loudly when the winner disappears`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-double-race-missing"
        stubExperiment(experimentId)
        val anonymous = Assignment(
            experimentId = experimentId,
            variationKey = "treatment",
            installationId = installationId,
        )
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        } returns null andThen anonymous andThen null
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "treatment", principalId, installationId)
        } returns null
        coEvery { assignmentRepository.claimPrincipal(experimentId, installationId, principalId) } returns null

        assertFailsWith<IllegalStateException> {
            service.assignVariation(experimentId, principalId, installationId)
        }
        assertEquals(0, assignedEvents.size)
    }

    @Test
    fun `lost insert race fails loudly when no winner can be reloaded`() = cacheTest {
        val experimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-race-missing"
        stubExperiment(experimentId)
        coEvery {
            assignmentRepository.getByIdentity(experimentId, principalId, installationId)
        } returns null
        coEvery {
            assignmentRepository.addIfAbsent(experimentId, "treatment", principalId, installationId)
        } returns null

        assertFailsWith<IllegalStateException> {
            service.assignVariation(experimentId, principalId, installationId)
        }
        assertEquals(0, assignedEvents.size)
    }

    @Test
    fun `exclusion layer returns the existing other experiment assignment`() = cacheTest {
        val experimentId = UUID.random()
        val otherExperimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-layer"
        val layerId = UUID.random()
        stubExperiment(experimentId, exclusionLayerId = layerId)
        val layerAssignment = Assignment(
            experimentId = otherExperimentId,
            variationKey = "control",
            principalId = principalId,
            installationId = installationId,
        )
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns null
        coEvery { assignmentRepository.getByPrincipalInLayer(principalId, layerId) } returns layerAssignment

        assertEquals(layerAssignment, service.assignVariation(experimentId, principalId, installationId))
        assertEquals(0, assignedEvents.size)
        coVerify(exactly = 0) { assignmentRepository.addIfAbsent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { assignmentRepository.getByInstallationInLayer(any(), any()) }
    }

    @Test
    fun `exclusion layer falls back to installation lookup for authenticated request`() = cacheTest {
        val experimentId = UUID.random()
        val otherExperimentId = UUID.random()
        val principalId = UUID.random()
        val installationId = "installation-layer-fallback"
        val layerId = UUID.random()
        stubExperiment(experimentId, exclusionLayerId = layerId)
        val layerAssignment = Assignment(
            experimentId = otherExperimentId,
            variationKey = "control",
            installationId = installationId,
        )
        coEvery { assignmentRepository.getByIdentity(experimentId, principalId, installationId) } returns null
        coEvery { assignmentRepository.getByPrincipalInLayer(principalId, layerId) } returns null
        coEvery { assignmentRepository.getByInstallationInLayer(installationId, layerId) } returns layerAssignment

        assertEquals(layerAssignment, service.assignVariation(experimentId, principalId, installationId))
        coVerify(exactly = 0) { assignmentRepository.addIfAbsent(any(), any(), any(), any()) }
    }

    @Test
    fun `setStatus fails when the experiment does not exist`() = runTest {
        val experimentId = UUID.random()
        coEvery { experimentRepository.getById(experimentId) } returns null

        assertFailsWith<IllegalStateException> {
            service.setStatus(experimentId, ExperimentStatus.PAUSED)
        }
    }

    @Test
    fun `setStatus fails when the row disappears during the update`() = runTest {
        val experimentId = UUID.random()
        val experiment = stubExperiment(experimentId).copy(status = ExperimentStatus.RUNNING)
        coEvery { experimentRepository.getById(experimentId) } returns experiment
        coEvery { experimentRepository.updateStatus(experimentId, ExperimentStatus.PAUSED) } returns null

        assertFailsWith<IllegalStateException> {
            service.setStatus(experimentId, ExperimentStatus.PAUSED)
        }
    }

    @Test
    fun `delete captures experiment update publication failure`() = runTest {
        val experimentId = UUID.random()
        val experiment = stubExperiment(experimentId)
        val failure = IllegalStateException("pubsub unavailable")
        coEvery { experimentRepository.getById(experimentId) } returns experiment
        coEvery { experimentRepository.deleteById(experimentId) } returns Unit
        coEvery {
            pubSubService.publish(
                any(),
                any<SerializationStrategy<ExperimentUpdated>>(),
                any<ExperimentUpdated>(),
            )
        } throws failure

        service.delete(experimentId)

        coVerify(exactly = 1) {
            errorCapture.capture(
                failure,
                null,
                match { it["experimentId"] == experimentId.toString() && it["action"] == "DELETED" },
            )
        }
    }

    @Test
    fun `paged report goal and rollout event reads delegate without reshaping`() = runTest {
        val experimentId = UUID.random()
        coEvery { conversionGoalRepository.getByExperimentId(experimentId, 5, 10) } returns emptyList()
        coEvery {
            analysisReportRepository.getByExperimentIdForRevision(experimentId, "control", 3, 5, 10)
        } returns emptyList()
        coEvery { rolloutPolicyEventRepository.getByExperimentId(experimentId, 5, 10) } returns emptyList()

        assertEquals(emptyList(), service.getConversionGoals(experimentId, 5, 10))
        assertEquals(emptyList(), service.getAnalysisReportsForRevision(experimentId, "control", 3, 5, 10))
        assertEquals(emptyList(), service.getRolloutPolicyEvents(experimentId, 5, 10))
    }
}
