package bosca.experimentation.service

import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.ExperimentUpdateAction
import bosca.experimentation.model.ExperimentUpdated
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.FlagUpdateAction
import bosca.experimentation.model.FlagUpdated
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * High-value caching invariant tests for [FeatureFlagServiceImpl].
 *
 * Feature flag evaluation is the latency-critical hot path operators
 * notice during app startup, so the cache layer's correctness is more
 * important than its existence. These tests pin the invariants the
 * cache layer is supposed to provide:
 *
 *   1. **Repeat reads hit the cache.** Two consecutive `evaluate` calls
 *      for the same flag must call the repository at most once across
 *      the cache lifetime — otherwise the cache is decorative.
 *
 *   2. **Negative results are cached.** Looking up an unknown flag key
 *      twice must hit the repo only once. The "no flag" hot path is
 *      common in production (legacy keys, deleted flags) and pays the
 *      DB cost on every miss without negative caching.
 *
 *   3. **`evaluateAll` issues exactly one bulk query on a cold cache,
 *      then zero on a warm cache.** Per-flag `getByKey` calls inside
 *      the loop must NOT happen because the bulk resolver warms the
 *      per-flag cache as a side effect.
 *
 *   4. **A `FlagUpdated` pub/sub event invalidates the per-flag entry
 *      AND the active-keys list** — the next call goes back to the DB.
 *
 *   5. **Running-experiment lookups cache positive AND negative
 *      results.** The "no experiment attached to this rule" path is
 *      the common case for most rules; without negative caching it
 *      hits the DB on every evaluation.
 *
 *   6. **An `ExperimentUpdated` pub/sub event drops only the affected
 *      flag's running-experiment entries** (prefix removal). Unrelated
 *      flags' entries must survive the invalidation.
 *
 * Each test wires a real `MutableSharedFlow` for one of the two
 * pub/sub channels so it can simulate an event landing while the
 * service is running, exactly as Redis or NATS would deliver one in
 * production.
 */
class FeatureFlagCacheTest {

    private val flagRepository = mockk<FeatureFlagRepository>()
    private val experimentRepository = mockk<ExperimentRepository>(relaxed = true)
    private val assignmentRepository = mockk<AssignmentRepository>(relaxed = true)
    private val flagAssignmentRepository =
        mockk<bosca.experimentation.repository.FlagAssignmentRepository>(relaxed = true)
    private val analyticsClient = mockk<bosca.analytics.server.ServerAnalyticsClient>(relaxed = true)
    private val segmentService = mockk<SegmentService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val experimentService = mockk<ExperimentService>(relaxed = true)
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

    private val booleanVariations = buildJsonArray {
        add(buildJsonObject {
            put("key", "off"); put("name", "Off"); put("description", ""); put("value", false)
        })
        add(buildJsonObject {
            put("key", "on"); put("name", "On"); put("description", ""); put("value", true)
        })
    }

    private fun makeFlag(
        id: UUID = UUID.random(),
        key: String = "test-flag",
        status: FlagStatus = FlagStatus.ENABLED,
    ) = FeatureFlag(
        id = id,
        key = key,
        name = key,
        type = FlagType.BOOLEAN,
        status = status,
        variations = booleanVariations,
        defaultVariationKey = "off",
        targetingRules = null,
        salt = "test-salt",
    )

    @AfterTest
    fun teardown() {
        // Cancel the per-instance pub/sub subscriber coroutines so the
        // accumulating Dispatchers.Default workers across all tests in
        // the suite don't OOM the test JVM.
        runCatching { kotlinx.coroutines.runBlocking { service.shutdown() } }
    }

    @BeforeTest
    fun setup() {
        cacheSupport.installInDi()

        // The pub/sub subscribers always go through `subscribe(...)` on
        // service init. We don't need to drive real events through them
        // — invalidation is tested by calling `service.handleFlagUpdated`
        // / `handleExperimentUpdated` directly inside the test
        // coroutine, which is deterministic and avoids cross-thread
        // timing entirely. Returning an empty flow keeps the
        // subscriber loops parked harmlessly.
        every {
            pubSubService.subscribe(any(), any<DeserializationStrategy<Any>>())
        } returns emptyFlow()

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
        // Default: no experiments attached anywhere unless a test
        // overrides this. Negative-cache tests rely on this.
        coEvery { experimentRepository.getRunningByFlagAndRule(any(), any()) } returns null
    }

    // -----------------------------------------------------------------
    // Repeat reads hit the cache
    // -----------------------------------------------------------------

    @Test
    fun `two consecutive evaluate calls hit the repository at most once`() = runTest {
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag()
        cacheSupport.withFlagCache {
            service.evaluate("test-flag", null, "device-1", null)
            service.evaluate("test-flag", null, "device-2", null)
        }
        coVerify(exactly = 1) { flagRepository.getByKey("test-flag") }
    }

    @Test
    fun `consecutive evaluate calls in separate request scopes also hit the repo only once`() = runTest {
        // Cross-request hit: the local per-RequestCache map is gone,
        // so the second call exercises the remote tier (in-memory
        // here, Redis in production). The repo must NOT be touched a
        // second time.
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag()
        cacheSupport.withFlagCache {
            service.evaluate("test-flag", null, "device-1", null)
        }
        cacheSupport.withFlagCache {
            service.evaluate("test-flag", null, "device-2", null)
        }
        coVerify(exactly = 1) { flagRepository.getByKey("test-flag") }
    }

    // -----------------------------------------------------------------
    // Negative caching
    // -----------------------------------------------------------------

    @Test
    fun `unknown flag key is negatively cached and resolved only once`() = runTest {
        coEvery { flagRepository.getByKey("ghost") } returns null
        cacheSupport.withFlagCache {
            val a = service.evaluate("ghost", null, "u1", null)
            val b = service.evaluate("ghost", null, "u2", null)
            // Both fall back to the synthetic empty evaluation.
            assertEquals("", a.variationKey)
            assertEquals("", b.variationKey)
        }
        // Critical: the second lookup must come from the cache.
        // Without negative caching the unknown-key path stays on the
        // DB forever, exactly the situation production legacy keys
        // produce.
        coVerify(exactly = 1) { flagRepository.getByKey("ghost") }
    }

    // -----------------------------------------------------------------
    // evaluateAll bulk warming
    // -----------------------------------------------------------------

    @Test
    fun `evaluateAll issues exactly one bulk query and warms the per-flag cache`() = runTest {
        val flags = listOf(
            makeFlag(key = "flag-a"),
            makeFlag(key = "flag-b"),
            makeFlag(key = "flag-c"),
        )
        coEvery { flagRepository.getAllActive() } returns flags

        cacheSupport.withFlagCache {
            val results = service.evaluateAll(null, "u1", null)
            assertEquals(3, results.size)
        }
        // The bulk SELECT was issued exactly once; per-flag getByKey
        // was NOT called even though `evaluateAll`'s loop calls
        // `flagCache.get(key)` for every flag — the active-keys
        // resolver primed the per-flag cache as a side effect, so
        // each loop iteration was a hit.
        coVerify(exactly = 1) { flagRepository.getAllActive() }
        coVerify(exactly = 0) { flagRepository.getByKey(any()) }
    }

    @Test
    fun `subsequent evaluate after evaluateAll is a cache hit (warm-once benefit)`() = runTest {
        coEvery { flagRepository.getAllActive() } returns listOf(makeFlag(key = "flag-a"))
        cacheSupport.withFlagCache {
            service.evaluateAll(null, "u1", null)
            service.evaluate("flag-a", null, "u1", null)
        }
        // The per-flag cache was warmed by the bulk resolver; the
        // single-flag evaluate that followed must NOT have queried
        // the repo for it.
        coVerify(exactly = 0) { flagRepository.getByKey("flag-a") }
    }

    // -----------------------------------------------------------------
    // Pub/sub invalidation: flag side
    // -----------------------------------------------------------------

    @Test
    fun `handleFlagUpdated invalidates the per-flag cache so the next call hits the DB`() = runTest {
        val flag = makeFlag()
        coEvery { flagRepository.getByKey("test-flag") } returns flag

        cacheSupport.withFlagCache {
            service.evaluate("test-flag", null, "u1", null)
        }
        coVerify(exactly = 1) { flagRepository.getByKey("test-flag") }

        // Drive the invalidation handler directly. In production this
        // is what the FLAG_UPDATED_CHANNEL subscriber calls; the
        // subscriber plumbing is trivial pass-through and doesn't need
        // its own test, while running it cross-thread in a test is the
        // source of all the timing flakiness.
        cacheSupport.withFlagCache {
            service.handleFlagUpdated(
                FlagUpdated(
                    flagKey = "test-flag",
                    flagId = flag.id,
                    action = FlagUpdateAction.UPDATED,
                ),
            )
        }

        cacheSupport.withFlagCache {
            service.evaluate("test-flag", null, "u2", null)
        }
        // After invalidation, the next call must touch the repo.
        coVerify(exactly = 2) { flagRepository.getByKey("test-flag") }
    }

    @Test
    fun `handleFlagUpdated also invalidates the active-keys list`() = runTest {
        coEvery { flagRepository.getAllActive() } returns listOf(makeFlag(key = "flag-a"))

        cacheSupport.withFlagCache { service.evaluateAll(null, "u1", null) }
        coVerify(exactly = 1) { flagRepository.getAllActive() }

        cacheSupport.withFlagCache {
            service.handleFlagUpdated(
                FlagUpdated(
                    flagKey = "flag-a",
                    flagId = UUID.random(),
                    action = FlagUpdateAction.UPDATED,
                ),
            )
        }

        cacheSupport.withFlagCache { service.evaluateAll(null, "u2", null) }
        // The active set may have changed (status enable/disable),
        // so the bulk SELECT must run again on the next evaluateAll.
        coVerify(exactly = 2) { flagRepository.getAllActive() }
    }

    // -----------------------------------------------------------------
    // Running-experiment cache: positive + negative
    // -----------------------------------------------------------------

    @Test
    fun `running-experiment lookups cache the negative result and skip the DB on repeat`() = runTest {
        // Default mock returns null for getRunningByFlagAndRule. The
        // test asserts that two evaluations against a rules-bearing
        // flag don't both hit the experiment repository.
        val flagId = UUID.random()
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "rule-1")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "off"); put("weight", 1) })
                    })
                })
            })
        }
        val flag = makeFlag(id = flagId).copy(targetingRules = rules)
        coEvery { flagRepository.getByKey("test-flag") } returns flag

        cacheSupport.withFlagCache {
            service.evaluate("test-flag", null, "u1", null)
            service.evaluate("test-flag", null, "u2", null)
        }

        // Each evaluation looks up the rule's experiment plus the
        // default-path experiment (matched-rule short-circuits the
        // default path, so we should see at most one rule lookup +
        // zero default-path lookups across two calls).
        coVerify(exactly = 1) {
            experimentRepository.getRunningByFlagAndRule(flagId, "rule-1")
        }
    }

    @Test
    fun `ExperimentUpdated event drops only the affected flag's running-experiment entries`() = runTest {
        val targetFlagId = UUID.random()
        val otherFlagId = UUID.random()

        // Two flags, each with one rule. Stub running experiments for
        // both so the cache loads positive entries.
        val targetExperiment = Experiment(
            id = UUID.random(),
            featureFlagId = targetFlagId,
            controlVariationKey = "control",
            name = "exp",
            status = ExperimentStatus.RUNNING,
            targetingRuleId = "rule-1",
        )
        val otherExperiment = Experiment(
            id = UUID.random(),
            featureFlagId = otherFlagId,
            controlVariationKey = "control",
            name = "other-exp",
            status = ExperimentStatus.RUNNING,
            targetingRuleId = "rule-1",
        )
        coEvery {
            experimentRepository.getRunningByFlagAndRule(targetFlagId, "rule-1")
        } returns targetExperiment
        coEvery {
            experimentRepository.getRunningByFlagAndRule(otherFlagId, "rule-1")
        } returns otherExperiment

        val makeFlagWithRule: (UUID, String) -> FeatureFlag = { id, key ->
            val rules = buildJsonArray {
                add(buildJsonObject {
                    put("id", "rule-1")
                    put("conditions", buildJsonArray { })
                    put("rollout", buildJsonObject {
                        put("variationWeights", buildJsonArray {
                            add(buildJsonObject { put("variationKey", "off"); put("weight", 1) })
                        })
                    })
                })
            }
            makeFlag(id = id, key = key).copy(targetingRules = rules)
        }
        coEvery { flagRepository.getByKey("target-flag") } returns
            makeFlagWithRule(targetFlagId, "target-flag")
        coEvery { flagRepository.getByKey("other-flag") } returns
            makeFlagWithRule(otherFlagId, "other-flag")

        // Warm both flags' running-experiment entries.
        cacheSupport.withFlagCache {
            service.evaluate("target-flag", null, "u1", null)
            service.evaluate("other-flag", null, "u1", null)
        }
        coVerify(exactly = 1) { experimentRepository.getRunningByFlagAndRule(targetFlagId, "rule-1") }
        coVerify(exactly = 1) { experimentRepository.getRunningByFlagAndRule(otherFlagId, "rule-1") }

        // Invalidate ONLY the target flag's experiment entries via
        // the handler. Same direct-call rationale as the
        // handleFlagUpdated tests above.
        cacheSupport.withFlagCache {
            service.handleExperimentUpdated(
                ExperimentUpdated(
                    experimentId = targetExperiment.id,
                    flagId = targetFlagId,
                    action = ExperimentUpdateAction.STATUS_CHANGED,
                ),
            )
        }

        cacheSupport.withFlagCache {
            service.evaluate("target-flag", null, "u2", null)
            service.evaluate("other-flag", null, "u2", null)
        }
        // Target flag was invalidated → repository hit again.
        coVerify(exactly = 2) { experimentRepository.getRunningByFlagAndRule(targetFlagId, "rule-1") }
        // Other flag's entry survived prefix removal → still cached.
        coVerify(exactly = 1) { experimentRepository.getRunningByFlagAndRule(otherFlagId, "rule-1") }
    }
}
