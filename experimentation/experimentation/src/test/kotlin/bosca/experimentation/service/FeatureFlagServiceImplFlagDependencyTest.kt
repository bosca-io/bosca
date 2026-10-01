package bosca.experimentation.service

import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 5 — prerequisite / FlagDependency conditions.
 *
 * Tests cover the five correctness properties from requirements.md R5:
 *
 *   1. Dependency satisfied → parent rule matches.
 *   2. Dependency unsatisfied → parent rule does not match and the
 *      flag falls through to its default variation.
 *   3. Missing prerequisite flag → dependency false (`negate`
 *      inverts it as usual).
 *   4. Disabled prerequisite flag → dependency false (the required
 *      variation is not "being served").
 *   5. Runtime cycle detection → returns false without stack
 *      overflow, logs an error, and the evaluation completes
 *      successfully (flag falls through to its default).
 *
 * Uses the same mock setup as [FeatureFlagServiceImplTest] so the
 * cache/pubsub/request-context plumbing stays identical to the real
 * evaluation path.
 */
class FeatureFlagServiceImplFlagDependencyTest {

    private val flagRepository = mockk<FeatureFlagRepository>()
    private val experimentRepository = mockk<ExperimentRepository>(relaxed = true)
    private val assignmentRepository = mockk<AssignmentRepository>(relaxed = true)
    private val flagAssignmentRepository = mockk<bosca.experimentation.repository.FlagAssignmentRepository>(relaxed = true)
    private val analyticsClient = mockk<bosca.analytics.server.ServerAnalyticsClient>(relaxed = true)
    private val segmentService = mockk<SegmentService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val experimentService = mockk<bosca.experimentation.service.ExperimentService>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val cacheSupport = FlagCacheTestSupport()

    private val service by lazy {
        FeatureFlagServiceImpl(
            flagRepository,
            experimentRepository,
            assignmentRepository,
            flagAssignmentRepository,
            analyticsClient,
            segmentService,
            profileService,
            pubSubService,
            experimentService,
            json,
            bosca.observability.ErrorCapture.Noop,
        )
    }

    @AfterTest
    fun teardown() {
        runCatching { kotlinx.coroutines.runBlocking { service.shutdown() } }
    }

    @BeforeTest
    fun setup() {
        cacheSupport.installInDi()
        every {
            pubSubService.subscribe(any(), any<kotlinx.serialization.DeserializationStrategy<Any>>())
        } returns emptyFlow()
        coEvery { experimentRepository.getRunningByFlagAndRule(any(), any()) } returns null
    }

    private fun cacheTest(block: suspend () -> Unit) = runTest {
        cacheSupport.withFlagCache { block() }
    }

    private val booleanVariations = buildJsonArray {
        add(buildJsonObject {
            put("key", "off"); put("name", "Off"); put("description", ""); put("value", false)
        })
        add(buildJsonObject {
            put("key", "on"); put("name", "On"); put("description", ""); put("value", true)
        })
    }

    private fun flag(
        key: String,
        targetingRules: JsonElement? = null,
        status: FlagStatus = FlagStatus.ENABLED,
        defaultVariationKey: String = "off",
    ) = FeatureFlag(
        id = UUID.random(),
        key = key,
        name = key,
        type = FlagType.BOOLEAN,
        status = status,
        variations = booleanVariations,
        defaultVariationKey = defaultVariationKey,
        targetingRules = targetingRules,
        salt = "salt-$key",
    )

    /**
     * Rule with a single FlagDependency condition requiring [requires]
     * to resolve to [variation], rolling 100% onto `on` when matched.
     */
    private fun flagDependencyRule(requires: String, variation: String, negate: Boolean = false): JsonElement =
        buildJsonArray {
            add(buildJsonObject {
                put("id", "rule-dep")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "FlagDependency")
                        put("flagKey", requires)
                        put("requiredVariationKey", variation)
                        put("negate", negate)
                    })
                })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject {
                            put("variationKey", "on")
                            put("weight", 1)
                        })
                    })
                })
            })
        }

    /**
     * Rule with no conditions that rolls 100% onto [variation]. Used
     * as the "prerequisite's own targeting" so the recursive
     * evaluation has a definite answer.
     */
    private fun unconditionalRollout(variation: String): JsonElement = buildJsonArray {
        add(buildJsonObject {
            put("id", "rule-$variation")
            put("conditions", buildJsonArray { })
            put("rollout", buildJsonObject {
                put("variationWeights", buildJsonArray {
                    add(buildJsonObject {
                        put("variationKey", variation)
                        put("weight", 1)
                    })
                })
            })
        })
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    fun `parent rule matches when prerequisite flag resolves to required variation`() = cacheTest {
        // Prerequisite: "cart" flag with an unconditional rollout to "on".
        val cart = flag("cart", targetingRules = unconditionalRollout("on"))
        // Parent: "checkout" with a FlagDependency on cart == on.
        val checkout = flag("checkout", targetingRules = flagDependencyRule("cart", "on"))

        coEvery { flagRepository.getByKey("cart") } returns cart
        coEvery { flagRepository.getByKey("checkout") } returns checkout

        val result = service.evaluate("checkout", null, "user-1", null)
        assertEquals("on", result.variationKey,
            "parent rule should match when prerequisite resolves to the required variation")
    }

    // -----------------------------------------------------------------
    // Unsatisfied dependency
    // -----------------------------------------------------------------

    @Test
    fun `parent rule does not match when prerequisite resolves to a different variation`() = cacheTest {
        // Prerequisite rolls 100% onto "off" — not the required "on".
        val cart = flag("cart", targetingRules = unconditionalRollout("off"))
        val checkout = flag("checkout", targetingRules = flagDependencyRule("cart", "on"))

        coEvery { flagRepository.getByKey("cart") } returns cart
        coEvery { flagRepository.getByKey("checkout") } returns checkout

        val result = service.evaluate("checkout", null, "user-1", null)
        // Dependency unsatisfied → parent rule skipped → default variation.
        assertEquals("off", result.variationKey,
            "parent should fall through to default when dependency unsatisfied, got ${result.variationKey}")
    }

    // -----------------------------------------------------------------
    // Missing prerequisite
    // -----------------------------------------------------------------

    @Test
    fun `missing prerequisite flag returns dependency false`() = cacheTest {
        val checkout = flag("checkout", targetingRules = flagDependencyRule("nope", "on"))
        coEvery { flagRepository.getByKey("nope") } returns null
        coEvery { flagRepository.getByKey("checkout") } returns checkout

        val result = service.evaluate("checkout", null, "user-1", null)
        assertEquals("off", result.variationKey,
            "missing prerequisite must fall through to default, got ${result.variationKey}")
    }

    // -----------------------------------------------------------------
    // Disabled prerequisite
    // -----------------------------------------------------------------

    @Test
    fun `disabled prerequisite flag returns dependency false`() = cacheTest {
        val cart = flag("cart", targetingRules = unconditionalRollout("on"), status = FlagStatus.DISABLED)
        val checkout = flag("checkout", targetingRules = flagDependencyRule("cart", "on"))

        coEvery { flagRepository.getByKey("cart") } returns cart
        coEvery { flagRepository.getByKey("checkout") } returns checkout

        val result = service.evaluate("checkout", null, "user-1", null)
        // A disabled prerequisite serves its default variation to
        // everyone; even if that happens to be "on" the condition
        // should fail because the prerequisite is not actively
        // serving the required variation through the rule chain.
        // Here the disabled prerequisite resolves to the default
        // variation ("off"), which is not "on", so the dependency
        // is false regardless.
        assertEquals("off", result.variationKey,
            "disabled prerequisite must fall through to default")
    }

    // -----------------------------------------------------------------
    // Cycle detection
    // -----------------------------------------------------------------

    @Test
    fun `A depends on B depends on A cycle returns false without stack overflow`() = cacheTest {
        // Flag A requires B=on; Flag B requires A=on. Evaluating A
        // recurses into B, which recurses back into A — the visited
        // set short-circuits the cycle.
        val flagA = flag("a", targetingRules = flagDependencyRule("b", "on"))
        val flagB = flag("b", targetingRules = flagDependencyRule("a", "on"))

        coEvery { flagRepository.getByKey("a") } returns flagA
        coEvery { flagRepository.getByKey("b") } returns flagB

        // Must not throw — a cycle at runtime is a misconfiguration,
        // not an availability issue.
        val result = service.evaluate("a", null, "user-1", null)
        assertEquals("off", result.variationKey,
            "cycle must fail closed to default variation, got ${result.variationKey}")
    }

    @Test
    fun `self-loop A depends on A returns false without recursion`() = cacheTest {
        // Degenerate cycle: a flag depending on itself. The visited
        // set catches it on entry.
        val flagA = flag("a", targetingRules = flagDependencyRule("a", "on"))
        coEvery { flagRepository.getByKey("a") } returns flagA

        val result = service.evaluate("a", null, "user-1", null)
        assertEquals("off", result.variationKey)
    }

    // -----------------------------------------------------------------
    // Negation
    // -----------------------------------------------------------------

    @Test
    fun `negated FlagDependency inverts the match`() = cacheTest {
        // Parent matches when cart is NOT on (i.e. cart == off).
        val cart = flag("cart", targetingRules = unconditionalRollout("off"))
        val checkout = flag(
            "checkout",
            targetingRules = flagDependencyRule("cart", "on", negate = true),
        )

        coEvery { flagRepository.getByKey("cart") } returns cart
        coEvery { flagRepository.getByKey("checkout") } returns checkout

        val result = service.evaluate("checkout", null, "user-1", null)
        // cart resolves to "off", required was "on", negated → condition true → rule matches → "on".
        assertEquals("on", result.variationKey,
            "negated dependency should match when prerequisite doesn't serve the required variation")
    }

    @Test
    fun `negated FlagDependency against a missing flag matches the rule`() = cacheTest {
        // Missing prerequisite is "dependency false" by default; negate → true → rule matches.
        val checkout = flag(
            "checkout",
            targetingRules = flagDependencyRule("missing", "on", negate = true),
        )
        coEvery { flagRepository.getByKey("missing") } returns null
        coEvery { flagRepository.getByKey("checkout") } returns checkout

        val result = service.evaluate("checkout", null, "user-1", null)
        assertEquals("on", result.variationKey,
            "negated FlagDependency on a missing flag should match the parent rule")
    }
}
