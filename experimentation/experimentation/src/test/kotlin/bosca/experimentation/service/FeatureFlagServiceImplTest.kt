package bosca.experimentation.service

import kotlin.test.assertTrue
import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mock-based tests for the variation-based flag model, evaluation engine, and
 * write-path decisions. PostgreSQL-backed assignment and service wiring coverage
 * lives in `ExperimentationRepositoryIntegrationTest`.
 */
class FeatureFlagServiceImplTest {

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

    /**
     * Shared in-memory cache fixture. Has to be installed in DI in
     * `setup()` before the lazy [service] field is touched, because
     * `FeatureFlagServiceImpl`'s `ServiceCache` fields resolve
     * `provide<CacheManager>()` at instance-init time.
     */
    private val cacheSupport = FlagCacheTestSupport()

    /**
     * Lazy so the service is constructed inside the first test, AFTER
     * `setup()` has installed the cache manager + request-cache
     * serializer in DI. Eager init would crash before any test runs
     * because the `ServiceCache(...)` factory calls `provide<CacheManager>()`.
     */
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

    /**
     * Convenience wrapper that runs a test block inside both
     * [runTest] and a fresh request-cache coroutine context. Every
     * test that touches `service.evaluate*` MUST go through this
     * helper, otherwise the cache calls inside the service will throw
     * `IllegalStateException("Request cache not found in coroutine context")`.
     */
    private fun cacheTest(block: suspend () -> Unit) = runTest {
        cacheSupport.withFlagCache { block() }
    }

    private val flagId = UUID.random()

    private val booleanVariations = buildJsonArray {
        add(buildJsonObject {
            put("key", "off")
            put("name", "Off")
            put("description", "")
            put("value", false)
        })
        add(buildJsonObject {
            put("key", "on")
            put("name", "On")
            put("description", "")
            put("value", true)
        })
    }

    private fun makeFlag(
        targetingRules: kotlinx.serialization.json.JsonElement? = null,
        defaultVariationKey: String = "off",
        salt: String = "test-salt"
    ) = FeatureFlag(
        id = flagId,
        key = "test-flag",
        name = "Test Flag",
        type = FlagType.BOOLEAN,
        status = FlagStatus.ENABLED,
        variations = booleanVariations,
        defaultVariationKey = defaultVariationKey,
        targetingRules = targetingRules,
        salt = salt
    )

    @AfterTest
    fun teardown() {
        // Cancel the per-instance pub/sub subscriber coroutines so they
        // don't leak onto Dispatchers.Default across the suite.
        runCatching { kotlinx.coroutines.runBlocking { service.shutdown() } }
    }

    @BeforeTest
    fun setup() {
        // Install the in-memory cache manager BEFORE constructing the
        // service (which is lazy precisely so this runs first).
        cacheSupport.installInDi()
        // The cache subscribers in `init { }` call `pubSubService.subscribe(...)`
        // and `collect { }` on the returned flow. Stub a closed empty
        // flow so the subscriber loops idle quietly without spinning.
        every {
            pubSubService.subscribe(any(), any<kotlinx.serialization.DeserializationStrategy<Any>>())
        } returns emptyFlow()

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
        coEvery { experimentRepository.getRunningByFlagAndRule(any(), any()) } returns null
    }

    @Test
    fun `evaluate returns default variation when flag has no rules`() = cacheTest {
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag()
        val result = service.evaluate("test-flag", null, "device-1", null)
        assertEquals("off", result.variationKey)
        assertEquals(JsonPrimitive(false), result.value)
    }

    @Test
    fun `assignment analytics event is emitted only for a persisted transition`() = cacheTest {
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag()
        coEvery {
            flagAssignmentRepository.record(any(), any(), any(), any(), any(), any())
        } returnsMany listOf(true, false)

        service.evaluate("test-flag", null, "device-1", null)
        service.evaluate("test-flag", null, "device-1", null)
        service.shutdown()

        coVerify(exactly = 2) {
            flagAssignmentRepository.record(flagId, "device-1", null, "off", any(), any())
        }
        coVerify(exactly = 1) {
            analyticsClient.captureForSubject(
                match { event ->
                    event.type == bosca.analytics.model.EventType.Assignment &&
                        event.element?.id == flagId.toString() &&
                        event.element?.type == "feature_flag"
                },
                null,
                "device-1",
                null,
            )
        }
    }

    @Test
    fun `authenticated evaluation passes installation for anonymous assignment promotion`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag()
        coEvery {
            flagAssignmentRepository.record(any(), any(), any(), any(), any(), any())
        } returns false

        service.evaluate("test-flag", principalId, "device-1", null)
        service.shutdown()

        coVerify(exactly = 1) {
            flagAssignmentRepository.record(
                flagId,
                "device-1",
                principalId,
                "off",
                any(),
                any(),
            )
        }
        coVerify(exactly = 0) { analyticsClient.captureForSubject(any(), any(), any(), any()) }
    }

    @Test
    fun `evaluate returns default variation when flag disabled`() = cacheTest {
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag().copy(status = FlagStatus.DISABLED)
        val result = service.evaluate("test-flag", null, "device-1", null)
        assertEquals("off", result.variationKey)
    }

    @Test
    fun `evaluate returns default variation when flag inactive`() = cacheTest {
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag().copy(status = FlagStatus.DRAFT)
        val result = service.evaluate("test-flag", null, "device-1", null)
        assertEquals("off", result.variationKey)
    }

    @Test
    fun `rule with single variation rollout serves that variation to matched users`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "rule-1")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "DeviceAttribute")
                        put("key", "platform")
                        put("operator", "EQUALS")
                        put("value", "IOS")
                        put("negate", false)
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
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag(targetingRules = rules)
        val device = bosca.analytics.model.Device(
            installationId = "device-1",
            manufacturer = "Apple",
            model = "iPhone15",
            platform = "IOS",
            primaryLocale = "en-US",
            systemName = "iOS",
            timezone = "UTC",
            type = "phone",
            version = "17.0",
        )
        val result = service.evaluate("test-flag", null, "device-1", device)
        assertEquals("on", result.variationKey)
        assertEquals(JsonPrimitive(true), result.value)
    }

    @Test
    fun `login keeps a previously evaluated rollout value`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "rule-login")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject {
                            put("variationKey", "off")
                            put("weight", 50)
                        })
                        add(buildJsonObject {
                            put("variationKey", "on")
                            put("weight", 50)
                        })
                    })
                })
            })
        }
        val installationId = "device-login"
        // Under principal-based bucketing this UUID hashes to "off", while
        // the installation hashes to "on", making a login rebucket visible.
        val principalId = UUID.parse("00000000-0000-0000-0000-000000000004")
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag(targetingRules = rules)

        val anonymous = service.evaluate("test-flag", null, installationId, null)
        val authenticated = service.evaluate("test-flag", principalId, installationId, null)

        assertEquals("on", anonymous.variationKey)
        assertEquals(anonymous.variationKey, authenticated.variationKey)
    }

    @Test
    fun `rule with 80-20 split serves both variations across users`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "rule-rollout")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject {
                            put("variationKey", "off")
                            put("weight", 80)
                        })
                        add(buildJsonObject {
                            put("variationKey", "on")
                            put("weight", 20)
                        })
                    })
                })
            })
        }
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag(targetingRules = rules)

        var onCount = 0
        var offCount = 0
        for (i in 0 until 200) {
            val result = service.evaluate("test-flag", null, "user-$i", null)
            when (result.variationKey) {
                "on" -> onCount++
                "off" -> offCount++
            }
        }
        // Roughly 20% should land on "on" — allow generous tolerance for sample noise.
        val onInRange = onCount in 20..60
        assertTrue(onInRange, "Expected ~20% on, got $onCount/200")
        val offInRange = offCount in 140..180
        assertTrue(offInRange, "Expected ~80% off, got $offCount/200")
    }

    @Test
    fun `widening a rollout keeps existing on-bucket users in on`() = cacheTest {
        // The contiguous-bucket property: a user who was 'on' in 80/20 should still be 'on' in 70/30.
        val narrow = buildJsonArray {
            add(buildJsonObject {
                put("id", "rule-rollout")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "off"); put("weight", 80) })
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 20) })
                    })
                })
            })
        }
        val wide = buildJsonArray {
            add(buildJsonObject {
                put("id", "rule-rollout")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "off"); put("weight", 70) })
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 30) })
                    })
                })
            })
        }

        // Find users that were "on" under the 80/20 split
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag(targetingRules = narrow)
        val onUsersNarrow = mutableSetOf<String>()
        for (i in 0 until 200) {
            val result = service.evaluate("test-flag", null, "user-$i", null)
            if (result.variationKey == "on") onUsersNarrow.add("user-$i")
        }

        // Re-evaluate the same users under the 70/30 split — they should all still be "on"
        coEvery { flagRepository.getByKey("test-flag") } returns makeFlag(targetingRules = wide)
        for (user in onUsersNarrow) {
            val result = service.evaluate("test-flag", null, user, null)
            assertEquals("on", result.variationKey, "User $user was on under 80/20 but is ${result.variationKey} under 70/30")
        }
    }

    @Test
    fun `add rejects flag with default variation key not in palette`() = cacheTest {
        val input = FeatureFlagInput(
            key = "bad",
            name = "Bad",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "missing"
        )
        assertFailsWith<IllegalArgumentException> { service.add(input) }
    }

    @Test
    fun `add rejects targeting rule referencing unknown variation`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "r1")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject {
                            put("variationKey", "purple")
                            put("weight", 1)
                        })
                    })
                })
            })
        }
        val input = FeatureFlagInput(
            key = "bad",
            name = "Bad",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = rules
        )
        val ex = assertFailsWith<IllegalArgumentException> { service.add(input) }
        assertTrue(ex.message?.contains("purple") == true)
    }

    @Test
    fun `add rejects variations of wrong type`() = cacheTest {
        val badVariations = buildJsonArray {
            add(buildJsonObject {
                put("key", "v1")
                put("name", "V1")
                put("description", "")
                put("value", "this is a string but flag type is BOOLEAN")
            })
        }
        val input = FeatureFlagInput(
            key = "bad",
            name = "Bad",
            type = FlagType.BOOLEAN,
            variations = badVariations,
            defaultVariationKey = "v1"
        )
        assertFailsWith<IllegalArgumentException> { service.add(input) }
    }

    @Test
    fun `add rejects duplicate rule ids`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "dup")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "off"); put("weight", 1) })
                    })
                })
            })
            add(buildJsonObject {
                put("id", "dup")
                put("conditions", buildJsonArray { })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                    })
                })
            })
        }
        val input = FeatureFlagInput(
            key = "dup",
            name = "Dup",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = rules
        )
        val ex = assertFailsWith<IllegalArgumentException> { service.add(input) }
        assertTrue(ex.message?.contains("Duplicate rule id") == true)
    }
}
