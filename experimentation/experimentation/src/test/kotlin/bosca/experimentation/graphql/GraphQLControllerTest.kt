@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.graphql

import bosca.analytics.model.Device
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.experimentation.jobs.ExperimentAnalysisJob
import bosca.experimentation.jobs.ExperimentResultAggregationJob
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.FlagEvaluation
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.FlagUpdateAction
import bosca.experimentation.model.FlagUpdated
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.VariationAssignmentCount
import bosca.experimentation.service.ExclusionLayerService
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.experimentation.repository.RolloutPolicyEventRepository
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.slot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the thin GraphQL mutation / query / type
 * controllers in the experimentation module. Individually each
 * file is a dozen lines of delegation, but together they are the
 * entire public surface of the experimentation GraphQL schema —
 * and every admin-UI mutation goes through them. These tests pin:
 *
 *   1. Admin-only mutations call `verifyHasAdminGroup` before
 *      forwarding to the service. A typo in the auth check here
 *      would silently allow anonymous callers to mutate flags
 *      and experiments.
 *   2. Query-layer authorization (admin vs. anonymous) correctly
 *      scrubs experiment ids from flag evaluations for non-admin
 *      callers. Leaking the experiment id would let an attacker
 *      probe bucketing by rotating device attributes.
 *   3. The `evaluate` / `evaluateAll` paths translate
 *      [AnalyticsDeviceInput] into the wire-format [Device] with
 *      null-to-empty-string semantics.
 *   4. Pagination limit clamping on every list endpoint that
 *      accepts an operator-supplied `limit`.
 *
 * Each test builds a fresh set of mocks so the service boundary
 * is clearly cut and no test leaks state into another.
 */
class GraphQLControllerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val flagId = UUID.random()
    private val experimentId = UUID.random()

    /**
     * Installs a Json provider in DI for the tests that exercise
     * job-enqueue paths. The `Job()` inline factory in
     * `bosca.sharedqueue.jobs` calls `provide<Json>()` to serialize
     * the job definition; without a registered provider the call
     * throws.
     */
    @BeforeTest
    fun installJsonProvider() {
        mockkObject(ProviderRegistry)
        every { ProviderRegistry.get(Json::class) } returns object : ObjectProvider<Json> {
            override val type = Json::class
            override suspend fun get(): Json = json
        }
    }

    @AfterTest
    fun uninstallJsonProvider() {
        unmockkObject(ProviderRegistry)
    }

    // -----------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------

    private fun adminAuth(): AuthenticationContext = mockk(relaxed = true)
    private fun anonAuth(): AuthenticationContext = mockk(relaxed = true)

    private fun flag() = FeatureFlag(
        id = flagId,
        key = "ctrl-test",
        name = "Ctrl",
        description = "",
        type = FlagType.BOOLEAN,
        status = FlagStatus.ENABLED,
        variations = buildJsonArray {
            add(buildJsonObject {
                put("key", "off"); put("name", "Off"); put("description", ""); put("value", false)
            })
        },
        defaultVariationKey = "off",
        targetingRules = null,
        salt = "salt",
    )

    private fun experiment() = Experiment(
        id = experimentId,
        featureFlagId = flagId,
        controlVariationKey = "off",
        name = "Exp",
        description = "",
        hypothesis = "",
        status = ExperimentStatus.DRAFT,
    )

    // -----------------------------------------------------------------
    // FeatureFlagMutationController — admin gate
    // -----------------------------------------------------------------

    @Test
    fun `FeatureFlagMutationController delegates every mutation to the service behind admin gate`() = runTest {
        val service = mockk<FeatureFlagService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = FeatureFlagMutationController(service, group)
        val auth = adminAuth()
        val input = FeatureFlagInput(
            key = "k", name = "n", type = FlagType.BOOLEAN,
            variations = flag().variations, defaultVariationKey = "off",
        )

        coEvery { service.add(any()) } returns flag()
        coEvery { service.edit(any(), any()) } returns flag()
        coEvery { service.setStatus(any(), any()) } returns flag()
        coEvery { service.regenerateSalt(any()) } returns flag()
        coJustRun { service.delete(any()) }

        controller.add(auth, input)
        controller.edit(auth, flagId, input)
        controller.delete(auth, flagId)
        controller.setStatus(auth, flagId, FlagStatus.ENABLED)
        controller.regenerateSalt(auth, flagId)

        // Every mutation gated on the admin verification helper —
        // 5 calls for 5 mutations.
        coVerify(exactly = 5) { group.verifyHasAdminGroup(auth) }
        coVerify(exactly = 1) { service.add(input) }
        coVerify(exactly = 1) { service.edit(flagId, input) }
        coVerify(exactly = 1) { service.delete(flagId) }
        coVerify(exactly = 1) { service.setStatus(flagId, FlagStatus.ENABLED) }
        coVerify(exactly = 1) { service.regenerateSalt(flagId) }
    }

    @Test
    fun `FeatureFlagMutationController re-throws when admin gate rejects`() = runTest {
        val service = mockk<FeatureFlagService>(relaxed = true)
        val group = mockk<GroupEvaluator>()
        val controller = FeatureFlagMutationController(service, group)
        val auth = anonAuth()
        val input = FeatureFlagInput(
            key = "k", name = "n", type = FlagType.BOOLEAN,
            variations = flag().variations, defaultVariationKey = "off",
        )
        coEvery { group.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFails("admin gate failure must propagate out of the controller") {
            controller.add(auth, input)
        }
        coVerify(exactly = 0) { service.add(any()) }
    }

    // -----------------------------------------------------------------
    // FeatureFlagQueryController — evaluate path + experiment-id scrub
    // -----------------------------------------------------------------

    @Test
    fun `FeatureFlagQueryController evaluate returns experiment id for admin callers`() = runTest {
        val service = mockk<FeatureFlagService>(relaxed = true)
        val group = mockk<GroupEvaluator>()
        val controller = FeatureFlagQueryController(service, group)
        val auth = adminAuth()
        coEvery { group.hasAdminGroup(auth) } returns true
        every { auth.principal() } returns null
        coEvery { service.evaluate("f", null, "device-1", null) } returns FlagEvaluation(
            flagKey = "f",
            variationKey = "on",
            value = JsonPrimitive(true),
            experimentId = experimentId,
        )

        val result = controller.evaluate(
            authentication = auth, flagKey = "f",
            installationId = "device-1", device = null,
        )
        assertEquals(experimentId, result.experimentId,
            "admin callers should see the experiment id")
    }

    @Test
    fun `FeatureFlagQueryController evaluate scrubs experiment id for anonymous callers`() = runTest {
        val service = mockk<FeatureFlagService>(relaxed = true)
        val group = mockk<GroupEvaluator>()
        val controller = FeatureFlagQueryController(service, group)
        val auth = anonAuth()
        coEvery { group.hasAdminGroup(auth) } returns false
        every { auth.principal() } returns null
        coEvery { service.evaluate("f", null, "device-1", null) } returns FlagEvaluation(
            flagKey = "f",
            variationKey = "on",
            value = JsonPrimitive(true),
            experimentId = experimentId,
        )

        val result = controller.evaluate(
            authentication = auth, flagKey = "f",
            installationId = "device-1", device = null,
        )
        assertNull(result.experimentId,
            "anonymous callers must have experiment id scrubbed to prevent bucketing probes")
    }

    @Test
    fun `FeatureFlagQueryController evaluateAll scrubs experiment ids for anonymous callers`() = runTest {
        val service = mockk<FeatureFlagService>(relaxed = true)
        val group = mockk<GroupEvaluator>()
        val controller = FeatureFlagQueryController(service, group)
        val auth = anonAuth()
        coEvery { group.hasAdminGroup(auth) } returns false
        every { auth.principal() } returns null
        coEvery { service.evaluateAll(null, "device-1", null) } returns listOf(
            FlagEvaluation(
                flagKey = "f1", variationKey = "on",
                value = JsonPrimitive(true), experimentId = experimentId,
            ),
            FlagEvaluation(
                flagKey = "f2", variationKey = "off",
                value = JsonPrimitive(false), experimentId = null,
            ),
        )
        val result = controller.evaluateAll(
            authentication = auth, installationId = "device-1", device = null,
        )
        assertEquals(2, result.size)
        assertTrue(result.all { it.experimentId == null },
            "evaluateAll must scrub experiment ids for every row when caller is anonymous")
    }

    @Test
    fun `FeatureFlagQueryController forwards authenticated principal and preserves admin results`() = runTest {
        val service = mockk<FeatureFlagService>()
        val group = mockk<GroupEvaluator>()
        val controller = FeatureFlagQueryController(service, group)
        val auth = adminAuth()
        val principal = mockk<AuthenticatedPrincipal>()
        val principalId = UUID.random()
        val input = AnalyticsDeviceInput(installationId = "device-1", platform = "IOS")
        every { auth.principal() } returns principal
        every { principal.id } returns principalId
        coEvery { group.hasAdminGroup(auth) } returns true
        coEvery { service.evaluateAll(principalId, "device-1", any()) } returns listOf(
            FlagEvaluation("f", "on", JsonPrimitive(true), experimentId = experimentId),
        )
        coEvery { service.evaluate("f", principalId, "device-1", any()) } returns
            FlagEvaluation("f", "on", JsonPrimitive(true), experimentId = experimentId)

        val result = controller.evaluateAll(auth, "device-1", input)
        val single = controller.evaluate(auth, "f", "device-1", input)

        assertEquals(experimentId, result.single().experimentId)
        assertEquals(experimentId, single.experimentId)
        coVerify(exactly = 1) { service.evaluateAll(principalId, "device-1", any()) }
        coVerify(exactly = 1) { service.evaluate("f", principalId, "device-1", any()) }
    }

    @Test
    fun `FeatureFlagQueryController treats nil principal as anonymous identity`() = runTest {
        val service = mockk<FeatureFlagService>()
        val group = mockk<GroupEvaluator>()
        val controller = FeatureFlagQueryController(service, group)
        val auth = anonAuth()
        val principal = mockk<AuthenticatedPrincipal>()
        every { auth.principal() } returns principal
        every { principal.id } returns UUID.NIL
        coEvery { group.hasAdminGroup(auth) } returns false
        coEvery { service.evaluate("f", null, "device-1", null) } returns
            FlagEvaluation("f", "off", JsonPrimitive(false), experimentId = experimentId)
        coEvery { service.evaluateAll(null, "device-1", null) } returns emptyList()

        val result = controller.evaluate(auth, "f", "device-1", null)
        val all = controller.evaluateAll(auth, "device-1", null)

        assertNull(result.experimentId)
        assertTrue(all.isEmpty())
        coVerify(exactly = 1) { service.evaluate("f", null, "device-1", null) }
        coVerify(exactly = 1) { service.evaluateAll(null, "device-1", null) }
    }

    @Test
    fun `FeatureFlagQueryController all clamps limit to 100`() = runTest {
        val service = mockk<FeatureFlagService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = FeatureFlagQueryController(service, group)
        val auth = adminAuth()
        coEvery { service.getAll(0L, 100) } returns listOf(flag())
        controller.all(auth, offset = -5L, limit = 9999)
        // Negative offset clamped to 0, oversized limit clamped to 100.
        coVerify(exactly = 1) { service.getAll(0L, 100) }
    }

    @Test
    fun `FeatureFlagQueryController delegates admin lookups by id and key`() = runTest {
        val service = mockk<FeatureFlagService>()
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = FeatureFlagQueryController(service, group)
        val auth = adminAuth()
        val expected = flag()
        coEvery { service.getById(flagId) } returns expected
        coEvery { service.getByKey(expected.key) } returns expected

        assertEquals(expected, controller.flag(auth, flagId))
        assertEquals(expected, controller.flagByKey(auth, expected.key))
        coVerify(exactly = 2) { group.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `FeatureFlagQueryController device input translates to wire-format Device`() = runTest {
        val service = mockk<FeatureFlagService>(relaxed = true)
        val group = mockk<GroupEvaluator>()
        val controller = FeatureFlagQueryController(service, group)
        val auth = anonAuth()
        coEvery { group.hasAdminGroup(auth) } returns false
        every { auth.principal() } returns null

        // The evaluate() signature takes `device: Device?` so the
        // slot type must match. mockk's `captureNullable` takes a
        // `CapturingSlot<T?>`, and the non-null `capture(slot<T>)`
        // rejects nulls — the simplest path that keeps both happy
        // is to capture in an answer lambda.
        val capturedDevice = java.util.concurrent.atomic.AtomicReference<Device?>()
        coEvery {
            service.evaluate(any(), any(), any(), any())
        } answers {
            capturedDevice.set(arg(3) as Device?)
            FlagEvaluation(flagKey = "f", variationKey = "on", value = JsonPrimitive(true))
        }
        val input = AnalyticsDeviceInput(
            installationId = "device-1",
            platform = "IOS",
            primaryLocale = "en-US",
            // Leave some fields null — toDevice() should fill with empty strings.
        )
        controller.evaluate(
            authentication = auth, flagKey = "f",
            installationId = "device-1", device = input,
        )
        val captured = assertNotNull(capturedDevice.get())
        assertEquals("device-1", captured.installationId)
        assertEquals("IOS", captured.platform)
        assertEquals("en-US", captured.primaryLocale)
        // Nulls mapped to empty strings.
        assertEquals("", captured.manufacturer)
        assertEquals("", captured.version)
    }

    // -----------------------------------------------------------------
    // AnalyticsDeviceInput — pure mapping
    // -----------------------------------------------------------------

    @Test
    fun `AnalyticsDeviceInput toDevice fills every null field with empty string`() {
        val input = AnalyticsDeviceInput()
        val device = input.toDevice()
        assertEquals("", device.installationId)
        assertEquals("", device.manufacturer)
        assertEquals("", device.model)
        assertEquals("", device.platform)
        assertEquals("", device.primaryLocale)
        assertEquals("", device.systemName)
        assertEquals("", device.timezone)
        assertEquals("", device.type)
        assertEquals("", device.version)
    }

    @Test
    fun `AnalyticsDeviceInput toDevice preserves set fields verbatim`() {
        val input = AnalyticsDeviceInput(
            installationId = "i1",
            manufacturer = "Apple",
            model = "iPhone15",
            platform = "IOS",
            primaryLocale = "en-US",
            systemName = "iOS",
            timezone = "America/Los_Angeles",
            type = "phone",
            version = "17.0",
        )
        val device = input.toDevice()
        assertEquals("i1", device.installationId)
        assertEquals("Apple", device.manufacturer)
        assertEquals("iPhone15", device.model)
        assertEquals("IOS", device.platform)
        assertEquals("en-US", device.primaryLocale)
        assertEquals("iOS", device.systemName)
        assertEquals("America/Los_Angeles", device.timezone)
        assertEquals("phone", device.type)
        assertEquals("17.0", device.version)
    }

    // -----------------------------------------------------------------
    // FeatureFlagTypeController — pagination clamping and field forwarding
    // -----------------------------------------------------------------

    @Test
    fun `FeatureFlagTypeController forwards every plain field`() = runTest {
        val experimentService = mockk<ExperimentService>(relaxed = true)
        val flagService = mockk<FeatureFlagService>(relaxed = true)
        val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
        val controller = FeatureFlagTypeController(experimentService, flagService, groupEvaluator)
        val f = flag()
        assertEquals(f.id, controller.id(f))
        assertEquals("ctrl-test", controller.key(f))
        assertEquals("Ctrl", controller.name(f))
        assertEquals("", controller.description(f))
        assertEquals(FlagType.BOOLEAN, controller.type(f))
        assertEquals(FlagStatus.ENABLED, controller.status(f))
        assertEquals(f.variations, controller.variations(f))
        assertEquals("off", controller.defaultVariationKey(f))
        assertEquals("salt", controller.salt(adminAuth(), f))
        assertNull(controller.targetingRules(f))
        assertEquals(f.created, controller.created(f))
        assertEquals(f.modified, controller.modified(f))
    }

    @Test
    fun `assignment count and flag update type controllers forward their fields`() {
        val count = VariationAssignmentCount("treatment", 42)
        val countController = VariationAssignmentCountTypeController()
        assertEquals("treatment", countController.variationKey(count))
        assertEquals(42L, countController.assignmentCount(count))

        val update = FlagUpdated(
            flagKey = "flag",
            flagId = flagId,
            action = FlagUpdateAction.UPDATED,
        )
        val updateController = FlagUpdatedTypeController()
        assertEquals("flag", updateController.flagKey(update))
        assertEquals(flagId, updateController.flagId(update))
        assertEquals(FlagUpdateAction.UPDATED, updateController.action(update))
    }

    @Test
    fun `FeatureFlagTypeController experiments clamps limit to MAX_EXPERIMENTS_PAGE`() = runTest {
        val experimentService = mockk<ExperimentService>(relaxed = true)
        val flagService = mockk<FeatureFlagService>(relaxed = true)
        val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
        val controller = FeatureFlagTypeController(experimentService, flagService, groupEvaluator)
        coEvery { experimentService.getByFlagId(flagId, 0L, 100) } returns listOf(experiment())
        controller.experiments(flag(), offset = -100, limit = 10_000)
        coVerify(exactly = 1) { experimentService.getByFlagId(flagId, 0L, 100) }
    }

    @Test
    fun `FeatureFlagTypeController returns current variation assignments`() = runTest {
        val experimentService = mockk<ExperimentService>(relaxed = true)
        val flagService = mockk<FeatureFlagService>(relaxed = true)
        val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
        val controller = FeatureFlagTypeController(experimentService, flagService, groupEvaluator)
        coEvery { flagService.getVariationAssignments(any()) } returns
            listOf(
                VariationAssignmentCount(
                    variationKey = "on",
                    assignmentCount = 42,
                )
            )
        val result = controller.variationAssignments(flag())
        assertEquals(1, result.size)
        assertEquals(42, result.single().assignmentCount)
        coVerify(exactly = 1) { flagService.getVariationAssignments(flagId) }
    }

    // -----------------------------------------------------------------
    // ExperimentMutationController — admin gate + mutation forwarding
    // -----------------------------------------------------------------

    @Test
    fun `ExperimentMutationController forwards every mutation behind admin gate`() = runTest {
        val service = mockk<ExperimentService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val controller = ExperimentMutationController(service, group, jobQueue)
        val auth = adminAuth()
        coEvery { service.add(any()) } returns experiment()
        coEvery { service.edit(any(), any()) } returns experiment()
        coEvery { service.setStatus(any(), any()) } returns experiment()

        val input = ExperimentInput(
            featureFlagId = flagId,
            controlVariationKey = "off",
            name = "n",
            hypothesis = "",
            activationFilter = ExperimentActivationFilterInput(
                eventType = GraphQLEventType.Impression,
                elementType = "page",
                pagePathPrefixes = listOf("/articles/"),
            ),
        )
        val serviceInput = input.toServiceInput()
        controller.add(auth, input)
        controller.edit(auth, experimentId, input)
        controller.delete(auth, experimentId)
        controller.setStatus(auth, experimentId, ExperimentStatus.RUNNING)

        // 4 mutations → 4 admin checks.
        coVerify(exactly = 4) { group.verifyHasAdminGroup(auth) }
        coVerify(exactly = 1) { service.add(serviceInput) }
        coVerify(exactly = 1) { service.edit(experimentId, serviceInput) }
        coVerify(exactly = 1) { service.delete(experimentId) }
        coVerify(exactly = 1) { service.setStatus(experimentId, ExperimentStatus.RUNNING) }
    }

    @Test
    fun `ExperimentMutationController aggregateResults and analyze enqueue jobs`() = runTest {
        val service = mockk<ExperimentService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val controller = ExperimentMutationController(service, group, jobQueue)
        val auth = adminAuth()

        val aggregated = controller.aggregateResults(auth, experimentId)
        val analyzed = controller.analyze(auth, experimentId)

        assertTrue(aggregated)
        assertTrue(analyzed)
        // Each mutation enqueues its own job on the experimentation
        // queue; verify both jobs were handed to jobQueue.enqueue.
        coVerify(exactly = 2) { jobQueue.enqueue(any()) }
        coVerify(exactly = 2) { group.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `ExperimentMutationController conversion goal mutations forward behind admin gate`() = runTest {
        val service = mockk<ExperimentService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val controller = ExperimentMutationController(service, group, jobQueue)
        val auth = adminAuth()
        val goalId = UUID.random()
        val goal = ConversionGoal(
            id = goalId,
            experimentId = experimentId,
            name = "Signups",
            metricType = GoalMetricType.UNIQUE_CONVERSION,
        )
        coEvery { service.addConversionGoal(experimentId, any()) } returns goal
        coEvery { service.editConversionGoal(goalId, any()) } returns goal

        val gqlInput = ConversionGoalInput(
            name = "Signups",
            eventType = GraphQLEventType.Interaction,
            metricType = GoalMetricType.UNIQUE_CONVERSION,
        )
        assertEquals(goal, controller.addConversionGoal(auth, experimentId, gqlInput))
        assertEquals(goal, controller.editConversionGoal(auth, goalId, gqlInput))
        assertEquals(true, controller.deleteConversionGoal(auth, goalId))

        coVerify(exactly = 3) { group.verifyHasAdminGroup(auth) }
        coVerify(exactly = 1) { service.addConversionGoal(experimentId, any()) }
        coVerify(exactly = 1) { service.editConversionGoal(goalId, any()) }
        coVerify(exactly = 1) { service.deleteConversionGoal(goalId) }
    }
}
