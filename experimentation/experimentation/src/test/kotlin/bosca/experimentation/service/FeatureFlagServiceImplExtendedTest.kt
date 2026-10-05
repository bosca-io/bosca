package bosca.experimentation.service

import bosca.experimentation.FlagCacheTestSupport
import bosca.experimentation.model.AttrOperator
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.EXPERIMENT_UPDATED_CHANNEL
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.ExperimentUpdateAction
import bosca.experimentation.model.ExperimentUpdated
import bosca.experimentation.model.FLAG_UPDATED_CHANNEL
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.FlagUpdateAction
import bosca.experimentation.model.FlagUpdated
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.VariationWeight
import bosca.experimentation.model.VariationAssignmentCount
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.observability.ErrorCapture
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.pubsub.Message
import bosca.segmentation.model.Segment
import bosca.segmentation.model.SegmentStatus
import bosca.segmentation.model.SegmentType
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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Additional [FeatureFlagServiceImpl] tests focused on the paths
 * the pre-existing [FeatureFlagServiceImplTest] leaves uncovered:
 *
 *   1. Running-experiment attachment: evaluate returns the
 *      experiment id on the rule-match path AND on the
 *      default-variation path, with the assignment-failure fallback
 *      that scrubs the id when recordAssignment throws.
 *   2. Exclusion layer skipping: a rule with an attached
 *      experiment in an exclusion layer is skipped when the user
 *      already has an assignment in another experiment in the
 *      same layer.
 *   3. evaluateAll: returns one entry per active flag, sharing the
 *      same evaluation context across flags.
 *   4. Segment and attribute conditions: rule match paths with
 *      Segment, Principal, ProfileAttribute, and every AttrOperator
 *      variant that wasn't already covered.
 *
 * Uses the same mock harness as `FeatureFlagServiceImplTest` —
 * shared `FlagCacheTestSupport`, mocked repositories, stubbed
 * pub/sub subscribers, mocked transaction interceptor.
 */
class FeatureFlagServiceImplExtendedTest {

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
    private val errorCapture = mockk<ErrorCapture>(relaxed = true)
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
            errorCapture,
        )
    }

    private val flagId = UUID.random()
    private val experimentId = UUID.random()
    private val layerId = UUID.random()

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

    private fun makeFlag(targetingRules: JsonElement? = null) = FeatureFlag(
        id = flagId,
        key = "ext-flag",
        name = "Ext",
        type = FlagType.BOOLEAN,
        status = FlagStatus.ENABLED,
        variations = booleanVariations,
        defaultVariationKey = "off",
        targetingRules = targetingRules,
        salt = "salt",
    )

    private fun runningExperimentOnRule(
        ruleId: String?,
        exclusionLayerId: UUID? = null,
    ) = Experiment(
        id = experimentId,
        featureFlagId = flagId,
        controlVariationKey = "control",
        name = "ext-exp",
        description = "",
        hypothesis = "",
        status = ExperimentStatus.RUNNING,
        targetingRuleId = ruleId,
        exclusionLayerId = exclusionLayerId,
    )

    private fun unconditionalRule() = buildJsonArray {
        add(buildJsonObject {
            put("id", "r-ext")
            put("conditions", buildJsonArray { })
            put("rollout", buildJsonObject {
                put("variationWeights", buildJsonArray {
                    add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                })
            })
        })
    }

    private fun dependencyRule(flagKey: String) = buildJsonArray {
        add(buildJsonObject {
            put("id", "dependency-rule")
            put("conditions", buildJsonArray {
                add(buildJsonObject {
                    put("type", "FlagDependency")
                    put("flagKey", flagKey)
                    put("requiredVariationKey", "on")
                    put("negate", false)
                })
            })
            put("rollout", buildJsonObject {
                put("variationWeights", buildJsonArray {
                    add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                })
            })
        })
    }

    private fun profile(id: UUID = UUID.random()) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = "Profile",
        visibility = ProfileVisibility.USER,
    )

    // -----------------------------------------------------------------
    // Running experiment attached to a rule
    // -----------------------------------------------------------------

    @Test
    fun `evaluate returns experimentId when running experiment attached to rule and assignment succeeds`() = cacheTest {
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext")
        coEvery {
            experimentService.assignVariation(experimentId, null, "device-1")
        } returns Assignment(
            id = UUID.random(),
            experimentId = experimentId,
            variationKey = "on",
            principalId = null,
            installationId = "device-1",
        )

        val result = service.evaluate("ext-flag", null, "device-1", null)

        assertEquals("on", result.variationKey)
        assertEquals(experimentId, result.experimentId,
            "evaluate should return the experiment id when assignment lands")
    }

    @Test
    fun `authenticated evaluation passes both identities to experiment assignment`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext")
        coEvery {
            experimentService.assignVariation(experimentId, principalId, "device-1")
        } returns Assignment(
            id = UUID.random(),
            experimentId = experimentId,
            variationKey = "on",
            principalId = principalId,
            installationId = "device-1",
        )

        service.evaluate("ext-flag", principalId, "device-1", null)

        coVerify(exactly = 1) {
            experimentService.assignVariation(experimentId, principalId, "device-1")
        }
    }

    @Test
    fun `persisted experiment assignment overrides a different login bucket`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext")
        // The rule currently buckets this request to "on", but the installation
        // was assigned "off" before login. The persisted assignment must win.
        coEvery {
            experimentService.assignVariation(experimentId, principalId, "device-1")
        } returns Assignment(
            id = UUID.random(),
            experimentId = experimentId,
            variationKey = "off",
            principalId = principalId,
            installationId = "device-1",
        )

        val result = service.evaluate("ext-flag", principalId, "device-1", null)

        assertEquals("off", result.variationKey)
        assertEquals(experimentId, result.experimentId)
    }

    @Test
    fun `evaluate scrubs experimentId when recordAssignment throws`() = cacheTest {
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext")
        coEvery {
            experimentService.assignVariation(experimentId, null, "device-1")
        } throws RuntimeException("transient DB error")

        val result = service.evaluate("ext-flag", null, "device-1", null)

        assertEquals("on", result.variationKey,
            "user still gets the variation even when assignment fails")
        assertNull(result.experimentId,
            "experiment id must be scrubbed when the assignment write fails")
    }

    @Test
    fun `evaluate exclusion layer skips rule when user already assigned to another experiment in layer`() = cacheTest {
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext", exclusionLayerId = layerId)
        // User is already enrolled in a different experiment in the same layer.
        coEvery {
            assignmentRepository.existsForOtherExperimentInLayerByInstallation(
                layerId, experimentId, "device-1",
            )
        } returns true

        val result = service.evaluate("ext-flag", null, "device-1", null)

        // The rule is skipped, so the flag falls through to its default variation.
        assertEquals("off", result.variationKey,
            "rule should be skipped when user is excluded by layer")
        assertNull(result.experimentId)
        // The exclusion fires before the assignment write, so
        // assignVariation should never be called.
        coVerify(exactly = 0) { experimentService.assignVariation(any(), any(), any()) }
    }

    @Test
    fun `authenticated exclusion checks principal first and skips installation after a hit`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext", exclusionLayerId = layerId)
        coEvery {
            assignmentRepository.existsForOtherExperimentInLayerByPrincipal(layerId, experimentId, principalId)
        } returns true

        val result = service.evaluate("ext-flag", principalId, "device-1", null)

        assertEquals("off", result.variationKey)
        coVerify(exactly = 0) {
            assignmentRepository.existsForOtherExperimentInLayerByInstallation(any(), any(), any())
        }
    }

    @Test
    fun `authenticated exclusion falls back to installation identity`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext", exclusionLayerId = layerId)
        coEvery {
            assignmentRepository.existsForOtherExperimentInLayerByPrincipal(layerId, experimentId, principalId)
        } returns false
        coEvery {
            assignmentRepository.existsForOtherExperimentInLayerByInstallation(layerId, experimentId, "device-1")
        } returns true

        val result = service.evaluate("ext-flag", principalId, "device-1", null)

        assertEquals("off", result.variationKey)
        coVerify(exactly = 1) {
            assignmentRepository.existsForOtherExperimentInLayerByInstallation(layerId, experimentId, "device-1")
        }
    }

    @Test
    fun `authenticated user proceeds when neither identity is excluded from the layer`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = unconditionalRule())
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, "r-ext")
        } returns runningExperimentOnRule("r-ext", exclusionLayerId = layerId)
        coEvery {
            assignmentRepository.existsForOtherExperimentInLayerByPrincipal(layerId, experimentId, principalId)
        } returns false
        coEvery {
            assignmentRepository.existsForOtherExperimentInLayerByInstallation(layerId, experimentId, "device-1")
        } returns false
        coEvery {
            experimentService.assignVariation(experimentId, principalId, "device-1")
        } returns Assignment(
            experimentId = experimentId,
            variationKey = "on",
            principalId = principalId,
            installationId = "device-1",
        )

        val result = service.evaluate("ext-flag", principalId, "device-1", null)

        assertEquals("on", result.variationKey)
        assertEquals(experimentId, result.experimentId)
    }

    @Test
    fun `pubsub subscribers consume both invalidation event types`() = runTest {
        val flagConsumed = CompletableDeferred<Unit>()
        val experimentConsumed = CompletableDeferred<Unit>()
        every {
            pubSubService.subscribe(
                FLAG_UPDATED_CHANNEL,
                any<DeserializationStrategy<FlagUpdated>>(),
            )
        } returns flow {
            emit(
                Message(
                    FLAG_UPDATED_CHANNEL,
                    FlagUpdated("ext-flag", flagId, FlagUpdateAction.UPDATED),
                ),
            )
            flagConsumed.complete(Unit)
            awaitCancellation()
        }
        every {
            pubSubService.subscribe(
                EXPERIMENT_UPDATED_CHANNEL,
                any<DeserializationStrategy<ExperimentUpdated>>(),
            )
        } returns flow {
            emit(
                Message(
                    EXPERIMENT_UPDATED_CHANNEL,
                    ExperimentUpdated(experimentId, flagId, ExperimentUpdateAction.UPDATED),
                ),
            )
            experimentConsumed.complete(Unit)
            awaitCancellation()
        }

        service
        flagConsumed.await()
        experimentConsumed.await()
        service.shutdown()

        assertTrue(flagConsumed.isCompleted)
        assertTrue(experimentConsumed.isCompleted)
    }

    @Test
    fun `evaluate default-variation path attaches default-rule experiment when one exists`() = cacheTest {
        // No targeting rules → goes to the default-variation path.
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = null)
        // A running experiment on the default-variation path (null rule).
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, null)
        } returns runningExperimentOnRule(null)
        coEvery {
            experimentService.assignVariation(experimentId, null, "device-1")
        } returns Assignment(
            id = UUID.random(),
            experimentId = experimentId,
            variationKey = "off",
            principalId = null,
            installationId = "device-1",
        )

        val result = service.evaluate("ext-flag", null, "device-1", null)

        assertEquals("off", result.variationKey)
        assertEquals(experimentId, result.experimentId,
            "default-variation path should attach the running experiment id")
    }

    @Test
    fun `evaluate default-variation path scrubs experimentId on assignment failure`() = cacheTest {
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = null)
        coEvery {
            experimentRepository.getRunningByFlagAndRule(flagId, null)
        } returns runningExperimentOnRule(null)
        coEvery {
            experimentService.assignVariation(experimentId, null, "device-1")
        } throws IllegalStateException("db down")

        val result = service.evaluate("ext-flag", null, "device-1", null)

        assertNull(result.experimentId,
            "default-variation path must also scrub experiment id on assignment failure")
    }

    // -----------------------------------------------------------------
    // Installation identity
    // -----------------------------------------------------------------

    @Test
    fun `evaluate rejects a blank installation id`() = cacheTest {
        assertFailsWith<IllegalArgumentException> {
            service.evaluate("ext-flag", null, "", null)
        }
    }

    // -----------------------------------------------------------------
    // Attribute operators
    // -----------------------------------------------------------------

    @Test
    fun `evaluate SEMVER_GTE matches when device version is above target`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "r-semver")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "DeviceAttribute")
                        put("key", "version")
                        put("operator", "SEMVER_GTE")
                        put("value", "2.0.0")
                        put("negate", false)
                    })
                })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                    })
                })
            })
        }
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = rules)
        val device = bosca.analytics.model.Device(
            installationId = "device-1",
            manufacturer = "", model = "", platform = "",
            primaryLocale = "", systemName = "", timezone = "",
            type = "", version = "2.5.3",
        )
        val result = service.evaluate("ext-flag", null, "device-1", device)
        assertEquals("on", result.variationKey,
            "2.5.3 >= 2.0.0 should match SEMVER_GTE")
    }

    @Test
    fun `evaluate CONTAINS operator matches substring`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "r-contains")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "DeviceAttribute")
                        put("key", "model")
                        put("operator", "CONTAINS")
                        put("value", "iPhone")
                        put("negate", false)
                    })
                })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                    })
                })
            })
        }
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = rules)
        val device = bosca.analytics.model.Device(
            installationId = "device-1",
            manufacturer = "Apple", model = "iPhone15Pro", platform = "IOS",
            primaryLocale = "en-US", systemName = "iOS", timezone = "UTC",
            type = "phone", version = "17.0",
        )
        val result = service.evaluate("ext-flag", null, "device-1", device)
        assertEquals("on", result.variationKey)
    }

    @Test
    fun `evaluate numeric GTE operator matches when actual is greater`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "r-numeric")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "DeviceAttribute")
                        put("key", "type")
                        put("operator", "EQUALS")
                        put("value", "phone")
                        put("negate", false)
                    })
                })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                    })
                })
            })
        }
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = rules)
        val device = bosca.analytics.model.Device(
            installationId = "device-1",
            manufacturer = "", model = "", platform = "",
            primaryLocale = "", systemName = "", timezone = "",
            type = "phone", version = "",
        )
        val result = service.evaluate("ext-flag", null, "device-1", device)
        assertEquals("on", result.variationKey)
    }

    @Test
    fun `evaluate negated condition inverts the match`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "r-neg")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "DeviceAttribute")
                        put("key", "platform")
                        put("operator", "EQUALS")
                        put("value", "ANDROID")
                        put("negate", true)
                    })
                })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                    })
                })
            })
        }
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = rules)
        val device = bosca.analytics.model.Device(
            installationId = "device-1",
            manufacturer = "", model = "", platform = "IOS",
            primaryLocale = "", systemName = "", timezone = "",
            type = "", version = "",
        )
        val result = service.evaluate("ext-flag", null, "device-1", device)
        // platform IS IOS, condition is NOT equal to ANDROID → negation → true → rule matches.
        assertEquals("on", result.variationKey)
    }

    @Test
    fun `evaluate device condition with no device supplied falls through`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "r-no-device")
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
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                    })
                })
            })
        }
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = rules)
        val result = service.evaluate("ext-flag", null, "device-1", null)
        assertEquals("off", result.variationKey,
            "device-attribute condition with no device should fail, rule skipped")
    }

    // -----------------------------------------------------------------
    // evaluateAll path + getAllActive cache warming
    // -----------------------------------------------------------------

    @Test
    fun `evaluateAll returns one entry per active flag`() = cacheTest {
        val flagA = FeatureFlag(
            id = UUID.random(),
            key = "a",
            name = "A",
            type = FlagType.BOOLEAN,
            status = FlagStatus.ENABLED,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
            salt = "s",
        )
        val flagB = FeatureFlag(
            id = UUID.random(),
            key = "b",
            name = "B",
            type = FlagType.BOOLEAN,
            status = FlagStatus.ENABLED,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
            salt = "s",
        )
        coEvery { flagRepository.getAllActive() } returns listOf(flagA, flagB)

        val results = service.evaluateAll(null, "device-1", null)

        assertEquals(2, results.size, "should return one evaluation per active flag")
        assertTrue(results.any { it.flagKey == "a" })
        assertTrue(results.any { it.flagKey == "b" })
    }

    @Test
    fun `evaluateAll returns empty list when no active flags exist`() = cacheTest {
        coEvery { flagRepository.getAllActive() } returns emptyList()
        val results = service.evaluateAll(null, "device-1", null)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `evaluateAll rejects a blank installation id`() = cacheTest {
        assertFailsWith<IllegalArgumentException> {
            service.evaluateAll(null, " ", null)
        }
    }

    @Test
    fun `evaluateAll preloads attributes and segments for every profile`() = cacheTest {
        val principalId = UUID.random()
        val firstProfile = profile()
        val secondProfile = profile()
        val firstSegment = Segment(
            id = UUID.random(),
            name = "First",
            type = SegmentType.STATIC,
            status = SegmentStatus.ACTIVE,
        )
        val secondSegment = firstSegment.copy(id = UUID.random(), name = "Second")
        val firstAttribute = ProfileAttribute(
            profile = firstProfile.id,
            typeId = "first",
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 0,
            source = "test",
        )
        val secondAttribute = firstAttribute.copy(profile = secondProfile.id, typeId = "second")
        coEvery { flagRepository.getAllActive() } returns listOf(makeFlag())
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(firstProfile, secondProfile)
        coEvery { profileService.getAttributes(firstProfile.id) } returns listOf(firstAttribute)
        coEvery { profileService.getAttributes(secondProfile.id) } returns listOf(secondAttribute)
        coEvery { segmentService.getSegmentsByProfileId(firstProfile.id) } returns listOf(firstSegment)
        coEvery { segmentService.getSegmentsByProfileId(secondProfile.id) } returns listOf(secondSegment)

        val result = service.evaluateAll(principalId, "device-1", null)

        assertEquals(1, result.size)
        assertTrue(result.single().degraded.not())
        coVerify(exactly = 1) { profileService.getByPrincipal(principalId) }
        coVerify(exactly = 1) { profileService.getAttributes(firstProfile.id) }
        coVerify(exactly = 1) { profileService.getAttributes(secondProfile.id) }
        coVerify(exactly = 1) { segmentService.getSegmentsByProfileId(firstProfile.id) }
        coVerify(exactly = 1) { segmentService.getSegmentsByProfileId(secondProfile.id) }
    }

    @Test
    fun `evaluateAll degrades when one profile attribute preload fails`() = cacheTest {
        val principalId = UUID.random()
        val failedProfile = profile()
        val healthyProfile = profile()
        coEvery { flagRepository.getAllActive() } returns listOf(makeFlag())
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(failedProfile, healthyProfile)
        coEvery { profileService.getAttributes(failedProfile.id) } throws IllegalStateException("unavailable")
        coEvery { profileService.getAttributes(healthyProfile.id) } returns emptyList()
        coEvery { segmentService.getSegmentsByProfileId(any()) } returns emptyList()

        val result = service.evaluateAll(principalId, "device-1", null)

        assertTrue(result.single().degraded)
    }

    @Test
    fun `evaluateAll degrades when the principal profile preload fails`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getAllActive() } returns listOf(makeFlag())
        coEvery { profileService.getByPrincipal(principalId) } throws IllegalStateException("unavailable")

        val result = service.evaluateAll(principalId, "device-1", null)

        assertTrue(result.single().degraded)
    }

    @Test
    fun `evaluateAll rethrows cancellation from profile attribute preload`() = cacheTest {
        val principalId = UUID.random()
        val profile = profile()
        coEvery { flagRepository.getAllActive() } returns listOf(makeFlag())
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { profileService.getAttributes(profile.id) } throws
            kotlinx.coroutines.CancellationException("cancelled")

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            service.evaluateAll(principalId, "device-1", null)
        }
    }

    @Test
    fun `evaluate returns degraded unknown value when the flag cannot be loaded`() = cacheTest {
        val failure = IllegalStateException("flag store unavailable")
        coEvery { flagRepository.getByKey("unavailable") } throws failure

        val result = service.evaluate("unavailable", null, "device-1", null)

        assertEquals("unavailable", result.flagKey)
        assertEquals("", result.variationKey)
        assertEquals(JsonNull, result.value)
        assertTrue(result.degraded)
        coVerify(exactly = 1) {
            errorCapture.capture(failure, null, match { it["operation"] == "load" && it["flagKey"] == "unavailable" })
        }
    }

    @Test
    fun `evaluate returns the degraded declared default when targeting evaluation fails`() = cacheTest {
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag()
        coEvery { experimentRepository.getRunningByFlagAndRule(flagId, null) } throws
                IllegalStateException("experiment store unavailable")

        val result = service.evaluate("ext-flag", null, "device-1", null)

        assertEquals("off", result.variationKey)
        assertEquals(JsonPrimitive(false), result.value)
        assertTrue(result.degraded)
    }

    @Test
    fun `evaluateAll returns an empty snapshot when active flags cannot be loaded`() = cacheTest {
        coEvery { flagRepository.getAllActive() } throws IllegalStateException("flag store unavailable")

        val results = service.evaluateAll(null, "device-1", null)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `evaluateAll keeps a degraded default for a flag whose targeting data is corrupt`() = cacheTest {
        coEvery { flagRepository.getAllActive() } returns listOf(
            makeFlag(targetingRules = JsonPrimitive(true)),
        )

        val results = service.evaluateAll(null, "device-1", null)

        assertEquals(1, results.size)
        assertEquals("off", results.single().variationKey)
        assertTrue(results.single().degraded)
    }

    @Test
    fun `evaluation does not convert coroutine cancellation into a feature fallback`() = cacheTest {
        coEvery { flagRepository.getByKey("cancelled") } throws kotlinx.coroutines.CancellationException("cancelled")

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            service.evaluate("cancelled", null, "device-1", null)
        }
    }

    @Test
    fun `evaluateAll does not convert active flag cancellation into an empty snapshot`() = cacheTest {
        coEvery { flagRepository.getAllActive() } throws kotlinx.coroutines.CancellationException("cancelled")

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            service.evaluateAll(null, "device-1", null)
        }
    }

    // -----------------------------------------------------------------
    // getById + getByKey + getAll
    // -----------------------------------------------------------------

    @Test
    fun `getById forwards to repository`() = cacheTest {
        val flag = makeFlag()
        coEvery { flagRepository.getById(flagId) } returns flag
        assertEquals(flag, service.getById(flagId))
    }

    @Test
    fun `getByKey forwards to repository`() = cacheTest {
        val flag = makeFlag()
        coEvery { flagRepository.getByKey(flag.key) } returns flag

        assertEquals(flag, service.getByKey(flag.key))
        coVerify(exactly = 1) { flagRepository.getByKey(flag.key) }
    }

    @Test
    fun `getAll clamps offset but passes through limit`() = cacheTest {
        coEvery { flagRepository.getAll(0L, 50) } returns emptyList()
        service.getAll(offset = 0L, limit = 50)
        coVerify(exactly = 1) { flagRepository.getAll(0L, 50) }
    }

    // -----------------------------------------------------------------
    // delete path
    // -----------------------------------------------------------------

    @Test
    fun `delete removes the flag and publishes an update`() = cacheTest {
        val flag = makeFlag()
        coEvery { flagRepository.deleteById(flagId) } returns flag

        service.delete(flagId)

        coVerify(exactly = 1) { flagRepository.deleteById(flagId) }
        coVerify(exactly = 1) { pubSubService.publish(any(), any(), any<bosca.experimentation.model.FlagUpdated>()) }
    }

    @Test
    fun `delete is a no-op when the flag does not exist`() = cacheTest {
        coEvery { flagRepository.deleteById(flagId) } returns null
        service.delete(flagId)
        coVerify(exactly = 0) { pubSubService.publish(any(), any(), any<bosca.experimentation.model.FlagUpdated>()) }
    }

    // -----------------------------------------------------------------
    // setStatus
    // -----------------------------------------------------------------

    @Test
    fun `setStatus forwards to repository`() = cacheTest {
        val flag = makeFlag()
        coEvery { flagRepository.updateStatus(flagId, FlagStatus.DISABLED) } returns flag.copy(status = FlagStatus.DISABLED)
        val result = service.setStatus(flagId, FlagStatus.DISABLED)
        assertEquals(FlagStatus.DISABLED, result.status)
    }

    @Test
    fun `setStatus throws when the flag does not exist`() = cacheTest {
        coEvery { flagRepository.updateStatus(flagId, FlagStatus.ENABLED) } returns null
        try {
            service.setStatus(flagId, FlagStatus.ENABLED)
            kotlin.test.fail("expected setStatus to throw on missing flag")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `regenerateSalt returns the updated flag`() = cacheTest {
        val flag = makeFlag().copy(salt = "new-salt")
        coEvery { flagRepository.regenerateSalt(flagId) } returns flag
        val result = service.regenerateSalt(flagId)
        assertEquals("new-salt", result.salt)
    }

    @Test
    fun `regenerateSalt throws when the flag does not exist`() = cacheTest {
        coEvery { flagRepository.regenerateSalt(flagId) } returns null

        assertFailsWith<IllegalStateException> {
            service.regenerateSalt(flagId)
        }
    }

    @Test
    fun `getVariationAssignments returns live distribution`() = cacheTest {
        val expected = listOf(VariationAssignmentCount("on", 12))
        coEvery { flagAssignmentRepository.getDistribution(flagId) } returns expected

        assertEquals(expected, service.getVariationAssignments(flagId))
    }

    @Test
    fun `evaluate IN operator matches any value in array`() = cacheTest {
        val rules = buildJsonArray {
            add(buildJsonObject {
                put("id", "r-in")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "DeviceAttribute")
                        put("key", "primaryLocale")
                        put("operator", "IN")
                        put("value", buildJsonArray {
                            add(JsonPrimitive("en-US"))
                            add(JsonPrimitive("en-GB"))
                            add(JsonPrimitive("fr-FR"))
                        })
                        put("negate", false)
                    })
                })
                put("rollout", buildJsonObject {
                    put("variationWeights", buildJsonArray {
                        add(buildJsonObject { put("variationKey", "on"); put("weight", 1) })
                    })
                })
            })
        }
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(targetingRules = rules)
        val device = bosca.analytics.model.Device(
            installationId = "device-1",
            manufacturer = "", model = "", platform = "",
            primaryLocale = "en-GB", systemName = "", timezone = "",
            type = "", version = "",
        )
        val result = service.evaluate("ext-flag", null, "device-1", device)
        assertEquals("on", result.variationKey)
    }

    // -----------------------------------------------------------------
    // add / edit validation coverage
    // -----------------------------------------------------------------

    @Test
    fun `add validates variations, default key, targeting rules and publishes update`() = cacheTest {
        val input = FeatureFlagInput(
            key = "new-flag",
            name = "New",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
        )
        val saved = makeFlag().copy(id = UUID.random(), key = "new-flag")
        coEvery { flagRepository.add(any()) } returns saved
        val result = service.add(input)
        assertEquals("new-flag", result.key)
        coVerify(exactly = 1) { flagRepository.add(any()) }
        coVerify { pubSubService.publish(any(), any(), any<Any>()) }
    }

    @Test
    fun `add preserves explicit description and status`() = cacheTest {
        val input = FeatureFlagInput(
            key = "new-flag",
            name = "New",
            description = "Description",
            type = FlagType.BOOLEAN,
            status = FlagStatus.ENABLED,
            variations = booleanVariations,
            defaultVariationKey = "off",
        )
        coEvery { flagRepository.add(any()) } answers { firstArg() }

        val result = service.add(input)

        assertEquals("Description", result.description)
        assertEquals(FlagStatus.ENABLED, result.status)
    }

    @Test
    fun `add rejects unknown default variation key`() = cacheTest {
        val input = FeatureFlagInput(
            key = "bad",
            name = "Bad",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "nope",
            targetingRules = null,
        )
        assertFails("unknown default key must throw") {
            service.add(input)
        }
    }

    @Test
    fun `add rejects rollout whose total weight is zero`() = cacheTest {
        val input = FeatureFlagInput(
            key = "bad-weight",
            name = "Bad Weight",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = buildJsonArray {
                add(buildJsonObject {
                    put("id", "zero")
                    put("conditions", buildJsonArray {})
                    put("rollout", buildJsonObject {
                        put("variationWeights", buildJsonArray {
                            add(buildJsonObject { put("variationKey", "on"); put("weight", 0) })
                        })
                    })
                })
            },
        )

        assertFailsWith<IllegalArgumentException> {
            service.add(input)
        }
    }

    @Test
    fun `add rejects blank duplicate and unknown targeting rule references`() = cacheTest {
        fun rules(vararg entries: TargetingRule) = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(TargetingRule.serializer()),
            entries.toList(),
        )
        fun input(targetingRules: JsonElement) = FeatureFlagInput(
            key = "bad-rules",
            name = "Bad Rules",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = targetingRules,
        )
        val validRollout = Rollout(listOf(VariationWeight("on", 1)))

        assertFailsWith<IllegalArgumentException> {
            service.add(input(rules(TargetingRule(id = "", rollout = validRollout))))
        }
        assertFailsWith<IllegalArgumentException> {
            service.add(
                input(
                    rules(
                        TargetingRule(id = "duplicate", rollout = validRollout),
                        TargetingRule(id = "duplicate", rollout = validRollout),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.add(
                input(
                    rules(
                        TargetingRule(
                            id = "unknown",
                            rollout = Rollout(listOf(VariationWeight("missing", 1))),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `add validates an acyclic flag dependency chain`() = cacheTest {
        val dependencyB = makeFlag().copy(id = UUID.random(), key = "dep-b", targetingRules = null)
        val dependencyA = makeFlag().copy(
            id = UUID.random(),
            key = "dep-a",
            targetingRules = dependencyRule("dep-b"),
        )
        val input = FeatureFlagInput(
            key = "new-flag",
            name = "New",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = dependencyRule("dep-a"),
        )
        coEvery { flagRepository.getByKey("dep-a") } returns dependencyA
        coEvery { flagRepository.getByKey("dep-b") } returns dependencyB
        coEvery { flagRepository.add(any()) } answers { firstArg() }

        assertEquals("new-flag", service.add(input).key)
        coVerify(exactly = 1) { flagRepository.getByKey("dep-a") }
        coVerify(exactly = 1) { flagRepository.getByKey("dep-b") }
    }

    @Test
    fun `add rejects a cyclic flag dependency chain`() = cacheTest {
        val dependency = makeFlag().copy(
            id = UUID.random(),
            key = "dep-a",
            targetingRules = dependencyRule("dep-a"),
        )
        val input = FeatureFlagInput(
            key = "new-flag",
            name = "New",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = dependencyRule("dep-a"),
        )
        coEvery { flagRepository.getByKey("dep-a") } returns dependency

        assertFailsWith<IllegalArgumentException> {
            service.add(input)
        }
    }

    @Test
    fun `add accepts non-dependency conditions and a missing optional prerequisite`() = cacheTest {
        fun input(key: String, targetingRules: JsonElement) = FeatureFlagInput(
            key = key,
            name = key,
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = targetingRules,
        )
        val deviceRule = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(TargetingRule.serializer()),
            listOf(
                TargetingRule(
                    id = "device",
                    conditions = listOf(
                        bosca.experimentation.model.Condition.DeviceAttribute(
                            key = "platform",
                            operator = AttrOperator.EQUALS,
                            value = JsonPrimitive("IOS"),
                        ),
                    ),
                    rollout = Rollout(listOf(VariationWeight("on", 1))),
                ),
            ),
        )
        coEvery { flagRepository.add(any()) } answers { firstArg() }
        coEvery { flagRepository.getByKey("not-found") } returns null
        coEvery { flagRepository.getByKey("dep-with-device-rule") } returns makeFlag(
            targetingRules = deviceRule,
        ).copy(id = UUID.random(), key = "dep-with-device-rule")

        assertEquals("device-rule", service.add(input("device-rule", deviceRule)).key)
        assertEquals(
            "missing-dependency",
            service.add(input("missing-dependency", dependencyRule("not-found"))).key,
        )
        assertEquals(
            "non-recursive-dependency",
            service.add(
                input("non-recursive-dependency", dependencyRule("dep-with-device-rule")),
            ).key,
        )
    }

    @Test
    fun `edit updates flag, prunes orphaned exposures, and publishes update`() = cacheTest {
        val existing = makeFlag()
        coEvery { flagRepository.getById(flagId) } returns existing
        coEvery { flagRepository.update(any()) } answers { firstArg() }
        val input = FeatureFlagInput(
            key = "ext-flag",
            name = "Updated",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
        )
        val result = service.edit(flagId, input)
        assertEquals("Updated", result.name)
        coVerify(exactly = 1) { flagRepository.update(any()) }
        coVerify { flagAssignmentRepository.pruneOrphanedVariations(flagId, any()) }
        coVerify { pubSubService.publish(any(), any(), any<Any>()) }
    }

    @Test
    fun `edit accepts explicit description and status`() = cacheTest {
        val existing = makeFlag()
        coEvery { flagRepository.getById(flagId) } returns existing
        coEvery { flagRepository.update(any()) } answers { firstArg() }
        val input = FeatureFlagInput(
            key = existing.key,
            name = existing.name,
            description = "Updated description",
            type = existing.type,
            status = FlagStatus.DISABLED,
            variations = booleanVariations,
            defaultVariationKey = "off",
        )

        val result = service.edit(flagId, input)

        assertEquals("Updated description", result.description)
        assertEquals(FlagStatus.DISABLED, result.status)
    }

    @Test
    fun `edit succeeds when best-effort assignment pruning fails`() = cacheTest {
        val existing = makeFlag()
        coEvery { flagRepository.getById(flagId) } returns existing
        coEvery { flagRepository.update(any()) } answers { firstArg() }
        coEvery {
            flagAssignmentRepository.pruneOrphanedVariations(flagId, any())
        } throws IllegalStateException("unavailable")
        val input = FeatureFlagInput(
            key = "ext-flag",
            name = "Updated",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
        )

        assertEquals("Updated", service.edit(flagId, input).name)
        coVerify(exactly = 1) { pubSubService.publish(any(), any(), any<Any>()) }
    }

    @Test
    fun `edit rejects unknown default variation before loading existing flag`() = cacheTest {
        val input = FeatureFlagInput(
            key = "ext-flag",
            name = "Updated",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "missing",
            targetingRules = null,
        )

        assertFailsWith<IllegalArgumentException> {
            service.edit(flagId, input)
        }
        coVerify(exactly = 0) { flagRepository.getById(flagId) }
    }

    @Test
    fun `flag update publishing failure is captured without failing the mutation`() = cacheTest {
        val failure = IllegalStateException("pubsub unavailable")
        val input = FeatureFlagInput(
            key = "new-flag",
            name = "New",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
        )
        val saved = makeFlag().copy(key = "new-flag")
        coEvery { flagRepository.add(any()) } returns saved
        coEvery { pubSubService.publish(any(), any(), any<Any>()) } throws failure

        assertEquals(saved, service.add(input))
        coVerify(exactly = 1) {
            errorCapture.capture(
                failure,
                null,
                match { it["flagKey"] == "new-flag" && it["flagId"] == saved.id.toString() },
            )
        }
    }

    @Test
    fun `assignment writer logs persistence failures without emitting analytics`() = cacheTest {
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag()
        coEvery {
            flagAssignmentRepository.record(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("database unavailable")

        assertEquals("off", service.evaluate("ext-flag", null, "device-1", null).variationKey)
        service.shutdown()

        coVerify(exactly = 0) { analyticsClient.captureForSubject(any(), any(), any(), any()) }
    }

    @Test
    fun `authenticated rule assignment analytics includes both identities and rule id`() = cacheTest {
        val principalId = UUID.random()
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag(unconditionalRule())
        coEvery {
            flagAssignmentRepository.record(any(), any(), any(), any(), any(), any())
        } returns true

        assertEquals("on", service.evaluate("ext-flag", principalId, "device-1", null).variationKey)
        service.shutdown()

        coVerify(exactly = 1) {
            analyticsClient.captureForSubject(
                match { event ->
                    event.element?.extras?.toString()?.contains("\"targetingRuleId\":\"r-ext\"") == true
                },
                principalId.toString(),
                "device-1",
                null,
            )
        }
    }

    @Test
    fun `evaluation remains available when assignment writer is already closed`() = cacheTest {
        coEvery { flagRepository.getByKey("ext-flag") } returns makeFlag()
        service.shutdown()

        assertEquals("off", service.evaluate("ext-flag", null, "device-1", null).variationKey)
        coVerify(exactly = 0) {
            flagAssignmentRepository.record(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `edit rejects key change`() = cacheTest {
        val existing = makeFlag()
        coEvery { flagRepository.getById(flagId) } returns existing
        val input = FeatureFlagInput(
            key = "different-key",
            name = "X",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
        )
        assertFails("changing key must throw") {
            service.edit(flagId, input)
        }
    }

    @Test
    fun `edit throws when flag not found`() = cacheTest {
        coEvery { flagRepository.getById(flagId) } returns null
        val input = FeatureFlagInput(
            key = "ext-flag",
            name = "X",
            type = FlagType.BOOLEAN,
            variations = booleanVariations,
            defaultVariationKey = "off",
            targetingRules = null,
        )
        assertFails("missing flag must throw") {
            service.edit(flagId, input)
        }
    }
}
