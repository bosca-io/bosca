package bosca.experimentation.service

import bosca.analytics.model.Device
import bosca.experimentation.model.AttrOperator
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.Condition
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.Variation
import bosca.experimentation.model.VariationWeight
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct unit tests for [FlagEvaluator] — the core evaluation engine
 * responsible for rule matching, condition evaluation, comparison
 * operators (including semver), and cycle detection. Exercises the
 * evaluator without the service layer's cache, pub-sub, and repository
 * scaffolding so edge cases are cheaply coverable.
 */
class FlagEvaluatorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val segmentService = mockk<SegmentService>()

    private fun evaluator(
        flagLookup: suspend (String) -> FeatureFlag? = { null },
        findRunningExperiment: suspend (UUID, String?) -> bosca.experimentation.model.Experiment? = { _, _ -> null },
        isExcludedFromLayer: suspend (UUID, UUID, UUID?, String) -> Boolean = { _, _, _, _ -> false },
        recordAssignment: suspend (UUID, String, UUID?, String) -> Assignment? = { _, _, _, _ -> null },
        recordFlagAssignment: (FeatureFlag, String?, UUID?, String, Device?, String) -> Unit =
            { _, _, _, _, _, _ -> },
    ) = FlagEvaluator(
        segmentService = segmentService,
        json = json,
        flagLookup = flagLookup,
        findRunningExperiment = findRunningExperiment,
        isExcludedFromLayer = isExcludedFromLayer,
        recordAssignment = recordAssignment,
        recordFlagAssignment = recordFlagAssignment,
    )

    private fun ctx(
        principalId: UUID? = UUID.random(),
        installationId: String = "install-1",
    ) = EvaluationContext(
        principalId = principalId,
        installationId = installationId,
        device = null,
        profileService = null,
    )

    private fun flag(
        key: String = "test-flag",
        status: FlagStatus = FlagStatus.ENABLED,
        variations: List<Variation> = listOf(
            Variation(key = "on", name = "On", value = JsonPrimitive(true)),
            Variation(key = "off", name = "Off", value = JsonPrimitive(false)),
        ),
        defaultVariationKey: String = "off",
        targetingRules: List<TargetingRule> = emptyList(),
    ): FeatureFlag {
        val variationsJson = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(Variation.serializer()),
            variations,
        )
        val rulesJson = if (targetingRules.isNotEmpty()) {
            json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(TargetingRule.serializer()),
                targetingRules,
            )
        } else null
        return FeatureFlag(
            id = UUID.random(),
            key = key,
            name = "Test Flag",
            status = status,
            variations = variationsJson,
            defaultVariationKey = defaultVariationKey,
            targetingRules = rulesJson,
        )
    }

    private fun testDevice(
        installationId: String = "i",
        manufacturer: String = "Test",
        model: String = "T1",
        platform: String = "test",
        primaryLocale: String = "en",
        systemName: String = "TestOS",
        timezone: String = "UTC",
        type: String = "phone",
        version: String = "1.0.0",
    ) = Device(
        installationId = installationId,
        manufacturer = manufacturer,
        model = model,
        platform = platform,
        primaryLocale = primaryLocale,
        systemName = systemName,
        timezone = timezone,
        type = type,
        version = version,
    )

    // ── Basic evaluation ─────────────────────────────────────────────────

    @Test
    fun `disabled flag returns default variation`() = runTest {
        val f = flag(status = FlagStatus.DRAFT)
        val result = evaluator().evaluateFlag(f, ctx())
        assertEquals("off", result.variationKey)
        assertEquals(JsonPrimitive(false), result.value)
    }

    @Test
    fun `enabled flag with no rules returns default variation`() = runTest {
        val f = flag()
        val result = evaluator().evaluateFlag(f, ctx())
        assertEquals("off", result.variationKey)
    }

    @Test
    fun `flag with empty variations returns empty key fallback`() = runTest {
        val f = flag(variations = emptyList())
        val result = evaluator().evaluateFlag(f, ctx())
        assertEquals("", result.variationKey)
        assertEquals(JsonNull, result.value)
    }

    @Test
    fun `matching rule returns its rollout variation`() = runTest {
        val rule = TargetingRule(
            id = "rule-1",
            conditions = emptyList(),
            rollout = Rollout(listOf(VariationWeight("on", 1))),
        )
        val f = flag(targetingRules = listOf(rule))
        val result = evaluator().evaluateFlag(f, ctx())
        assertEquals("on", result.variationKey)
        assertEquals(JsonPrimitive(true), result.value)
    }

    @Test
    fun `login keeps the installation rollout assignment`() = runTest {
        val rule = TargetingRule(
            id = "rule-login",
            rollout = Rollout(
                listOf(
                    VariationWeight("off", 50),
                    VariationWeight("on", 50),
                ),
            ),
        )
        val f = flag(targetingRules = listOf(rule))
        val installationId = "device-login"
        // This principal hashes to the opposite bucket from installationId.
        // The principal must therefore not become the rollout seed on login.
        val principalId = UUID.parse("00000000-0000-0000-0000-000000000004")

        val anonymous = evaluator().evaluateFlag(f, ctx(null, installationId))
        val authenticated = evaluator().evaluateFlag(f, ctx(principalId, installationId))

        assertEquals("on", anonymous.variationKey)
        assertEquals(anonymous.variationKey, authenticated.variationKey)
    }

    @Test
    fun `rule with unknown rollout variation falls through to default`() = runTest {
        val rule = TargetingRule(
            id = "rule-unknown",
            rollout = Rollout(listOf(VariationWeight("removed", 1))),
        )

        val result = evaluator().evaluateFlag(flag(targetingRules = listOf(rule)), ctx())

        assertEquals("off", result.variationKey)
    }

    @Test
    fun `persisted assignment is used only for the attached experiment and a current variation`() = runTest {
        val experimentId = UUID.random()
        val rule = TargetingRule(
            id = "rule-1",
            rollout = Rollout(listOf(VariationWeight("on", 1))),
        )
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            name = "Experiment",
            targetingRuleId = rule.id,
            controlVariationKey = "off",
        )
        val f = flag(targetingRules = listOf(rule))
        val wrongExperiment = Assignment(
            experimentId = UUID.random(),
            variationKey = "off",
            installationId = "install-1",
        )
        val removedVariation = Assignment(
            experimentId = experimentId,
            variationKey = "removed",
            installationId = "install-1",
        )

        val wrongExperimentResult = evaluator(
            findRunningExperiment = { _, _ -> experiment },
            recordAssignment = { _, _, _, _ -> wrongExperiment },
        ).evaluateFlag(f, ctx())
        val removedVariationResult = evaluator(
            findRunningExperiment = { _, _ -> experiment },
            recordAssignment = { _, _, _, _ -> removedVariation },
        ).evaluateFlag(f, ctx())

        assertEquals("on", wrongExperimentResult.variationKey)
        assertEquals(null, wrongExperimentResult.experimentId)
        assertEquals("on", removedVariationResult.variationKey)
        assertEquals(null, removedVariationResult.experimentId)
    }

    @Test
    fun `default experiment is skipped when exclusion layer already owns the subject`() = runTest {
        val experimentId = UUID.random()
        val layerId = UUID.random()
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            name = "Experiment",
            controlVariationKey = "off",
            exclusionLayerId = layerId,
        )
        var assignmentCalls = 0

        val result = evaluator(
            findRunningExperiment = { _, _ -> experiment },
            isExcludedFromLayer = { actualLayerId, actualExperimentId, _, _ ->
                assertEquals(layerId, actualLayerId)
                assertEquals(experimentId, actualExperimentId)
                true
            },
            recordAssignment = { _, _, _, _ -> assignmentCalls += 1; null },
        ).evaluateFlag(flag(), ctx())

        assertEquals("off", result.variationKey)
        assertEquals(null, result.experimentId)
        assertEquals(0, assignmentCalls)
    }

    @Test
    fun `default experiment id is dropped for an assignment with a mismatched variation`() = runTest {
        val experimentId = UUID.random()
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            name = "Experiment",
            controlVariationKey = "off",
        )
        val assignment = Assignment(
            experimentId = experimentId,
            variationKey = "on",
            installationId = "install-1",
        )

        val result = evaluator(
            findRunningExperiment = { _, _ -> experiment },
            recordAssignment = { _, _, _, _ -> assignment },
        ).evaluateFlag(flag(), ctx())

        assertEquals("off", result.variationKey)
        assertEquals(null, result.experimentId)
    }

    // ── Comparison operators ─────────────────────────────────────────────

    @Test
    fun `EQUALS operator matches string values`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.EQUALS, value = JsonPrimitive("iOS")
        )
        val device = testDevice(systemName = "iOS")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `NOT_EQUALS operator rejects matching values`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.NOT_EQUALS, value = JsonPrimitive("iOS")
        )
        val device = testDevice(systemName = "iOS")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `IN operator matches when value is in the list`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.IN,
            value = JsonArray(listOf(JsonPrimitive("iOS"), JsonPrimitive("Android")))
        )
        val device = testDevice(systemName = "Android")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `NOT_IN operator rejects when value is in the list`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.NOT_IN,
            value = JsonArray(listOf(JsonPrimitive("iOS"), JsonPrimitive("Android")))
        )
        val device = testDevice(systemName = "iOS")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `GREATER_THAN numeric comparison`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "version", operator = AttrOperator.GREATER_THAN, value = JsonPrimitive(2.0)
        )
        val device = testDevice(version = "3.5")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `numeric comparison returns false for non-numeric actual`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.GREATER_THAN, value = JsonPrimitive(2.0)
        )
        val device = testDevice(systemName = "notANumber")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `numeric comparison returns false when expected is not a number`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "version", operator = AttrOperator.GREATER_THAN, value = JsonPrimitive("notANumber")
        )
        val device = testDevice(version = "3.5")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    // ── Semver comparison ────────────────────────────────────────────────

    @Test
    fun `SEMVER_GTE compares dotted version strings`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "version", operator = AttrOperator.SEMVER_GTE, value = JsonPrimitive("2.14.0")
        )
        val device = testDevice(version = "2.14.3")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `SEMVER_LT works correctly`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "version", operator = AttrOperator.SEMVER_LT, value = JsonPrimitive("3.0.0")
        )
        val device = testDevice(version = "2.9.9")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `SEMVER_GTE returns false when expected is null`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "version", operator = AttrOperator.SEMVER_GTE, value = JsonNull
        )
        val device = testDevice(version = "1.0.0")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    // ── Condition negate ─────────────────────────────────────────────────

    @Test
    fun `negate inverts condition result`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.EQUALS, value = JsonPrimitive("iOS"), negate = true
        )
        val device = testDevice(systemName = "iOS")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `negate returns true when condition is false`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.EQUALS, value = JsonPrimitive("Android"), negate = true
        )
        val device = testDevice(systemName = "iOS")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    // ── Missing device attribute ─────────────────────────────────────────

    @Test
    fun `missing device attribute returns negate value`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "nonexistent", operator = AttrOperator.EQUALS, value = JsonPrimitive("x")
        )
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = null, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `missing device attribute with negate=true returns true`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "nonexistent", operator = AttrOperator.EQUALS, value = JsonPrimitive("x"), negate = true
        )
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = null, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `segment condition uses preloaded membership and rejects anonymous subject`() = runTest {
        val segmentId = UUID.random()
        val principalId = UUID.random()
        val condition = Condition.Segment(segmentId)
        val authenticated = EvaluationContext(
            principalId,
            "i",
            null,
            null,
            memberSegmentIds = setOf(segmentId),
        )
        val anonymous = EvaluationContext(
            null,
            "i",
            null,
            null,
            memberSegmentIds = setOf(segmentId),
        )

        assertTrue(evaluator().evaluateCondition(condition, authenticated))
        assertFalse(evaluator().evaluateCondition(condition, anonymous))
        assertTrue(evaluator().evaluateCondition(condition.copy(negate = true), anonymous))
    }

    @Test
    fun `principal condition matches only listed authenticated principal`() = runTest {
        val principalId = UUID.random()
        val condition = Condition.Principal(listOf(principalId))

        assertTrue(evaluator().evaluateCondition(condition, ctx(principalId)))
        assertFalse(evaluator().evaluateCondition(condition, ctx(UUID.random())))
        assertFalse(evaluator().evaluateCondition(condition, ctx(null)))
    }

    // ── FlagDependency cycle detection ───────────────────────────────────

    @Test
    fun `FlagDependency cycle detection returns negate value`() = runTest {
        val condition = Condition.FlagDependency(
            flagKey = "flag-a", requiredVariationKey = "on"
        )
        val evalCtx = ctx()
        evalCtx.visitedFlags.add("flag-a")
        val result = evaluator().evaluateCondition(condition, evalCtx)
        assertFalse(result)
    }

    @Test
    fun `FlagDependency resolves prerequisite flag`() = runTest {
        val prereqFlag = flag(key = "prereq", targetingRules = listOf(
            TargetingRule(
                id = "r",
                conditions = emptyList(),
                rollout = Rollout(listOf(VariationWeight("on", 1))),
            )
        ))
        val condition = Condition.FlagDependency(
            flagKey = "prereq", requiredVariationKey = "on"
        )
        val evalCtx = ctx()
        val result = evaluator(flagLookup = { if (it == "prereq") prereqFlag else null })
            .evaluateCondition(condition, evalCtx)
        assertTrue(result)
    }

    @Test
    fun `FlagDependency returns negate when prereq flag is disabled`() = runTest {
        val prereqFlag = flag(key = "prereq", status = FlagStatus.DRAFT)
        val condition = Condition.FlagDependency(
            flagKey = "prereq", requiredVariationKey = "on"
        )
        val evalCtx = ctx()
        val result = evaluator(flagLookup = { if (it == "prereq") prereqFlag else null })
            .evaluateCondition(condition, evalCtx)
        assertFalse(result)
    }

    @Test
    fun `FlagDependency returns negate when prereq flag not found`() = runTest {
        val condition = Condition.FlagDependency(
            flagKey = "missing", requiredVariationKey = "on"
        )
        val result = evaluator().evaluateCondition(condition, ctx())
        assertFalse(result)
    }

    // ── CONTAINS / STARTS_WITH / ENDS_WITH ───────────────────────────────

    @Test
    fun `CONTAINS operator works`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.CONTAINS, value = JsonPrimitive("dro")
        )
        val device = testDevice(systemName = "Android")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `STARTS_WITH operator works`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.STARTS_WITH, value = JsonPrimitive("An")
        )
        val device = testDevice(systemName = "Android")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `ENDS_WITH operator works`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.ENDS_WITH, value = JsonPrimitive("oid")
        )
        val device = testDevice(systemName = "Android")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `CONTAINS returns false when expected is null`() = runTest {
        val condition = Condition.DeviceAttribute(
            key = "os", operator = AttrOperator.CONTAINS, value = JsonNull
        )
        val device = testDevice(systemName = "Android")
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = device, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    // ── ProfileAttribute extraction ──────────────────────────────────────

    @Test
    fun `ProfileAttribute with missing attribute returns negate`() = runTest {
        val condition = Condition.ProfileAttribute(
            typeId = UUID.random().toString(), key = "country",
            operator = AttrOperator.EQUALS, value = JsonPrimitive("US"),
        )
        val evalCtx = EvaluationContext(
            principalId = UUID.random(), installationId = "i", device = null, profileService = null,
        )
        assertFalse(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `ProfileAttribute compares primitive value from matching attribute type`() = runTest {
        val typeId = "locale"
        val condition = Condition.ProfileAttribute(
            typeId = typeId,
            key = "country",
            operator = AttrOperator.EQUALS,
            value = JsonPrimitive("US"),
        )
        val attribute = ProfileAttribute(
            profile = UUID.random(),
            typeId = typeId,
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 0,
            source = "test",
            attributes = buildJsonObject { put("country", "US") },
        )
        val evalCtx = EvaluationContext(
            UUID.random(),
            "i",
            null,
            null,
            profileAttributes = listOf(attribute),
        )

        assertTrue(evaluator().evaluateCondition(condition, evalCtx))
    }

    @Test
    fun `ProfileAttribute rejects missing and non-primitive JSON values`() = runTest {
        val typeId = "locale"
        fun condition(key: String) = Condition.ProfileAttribute(
            typeId = typeId,
            key = key,
            operator = AttrOperator.EQUALS,
            value = JsonPrimitive("US"),
        )
        val attribute = ProfileAttribute(
            profile = UUID.random(),
            typeId = typeId,
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 0,
            source = "test",
            attributes = buildJsonObject {
                put("object", buildJsonObject { put("country", "US") })
            },
        )
        val evalCtx = EvaluationContext(
            UUID.random(),
            "i",
            null,
            null,
            profileAttributes = listOf(attribute),
        )

        assertFalse(evaluator().evaluateCondition(condition("missing"), evalCtx))
        assertFalse(evaluator().evaluateCondition(condition("object"), evalCtx))
    }

    @Test
    fun `remaining numeric and dotted version operators cover equality and ordering`() = runTest {
        suspend fun evaluate(operator: AttrOperator, actual: String, expected: JsonPrimitive): Boolean {
            val condition = Condition.DeviceAttribute("version", operator, expected)
            val context = EvaluationContext(
                UUID.random(),
                "i",
                testDevice(version = actual),
                null,
            )
            return evaluator().evaluateCondition(condition, context)
        }

        assertTrue(evaluate(AttrOperator.LESS_THAN, "1.5", JsonPrimitive(2.0)))
        assertTrue(evaluate(AttrOperator.GTE, "2.0", JsonPrimitive("2.0")))
        assertTrue(evaluate(AttrOperator.LTE, "2.0", JsonPrimitive(2.0)))
        assertTrue(evaluate(AttrOperator.SEMVER_GT, "2.0.1", JsonPrimitive("2.0.0")))
        assertTrue(evaluate(AttrOperator.SEMVER_LTE, "2.0", JsonPrimitive("2.0.0")))
        assertFalse(evaluate(AttrOperator.SEMVER_GT, "2.0.0", JsonPrimitive("2.0")))
    }

    @Test
    fun `collection operators reject a non-array expected value`() = runTest {
        val context = EvaluationContext(
            UUID.random(),
            "i",
            testDevice(systemName = "iOS"),
            null,
        )

        assertFalse(
            evaluator().evaluateCondition(
                Condition.DeviceAttribute("os", AttrOperator.IN, JsonPrimitive("iOS")),
                context,
            ),
        )
        assertTrue(
            evaluator().evaluateCondition(
                Condition.DeviceAttribute("os", AttrOperator.NOT_IN, JsonPrimitive("iOS")),
                context,
            ),
        )
    }

    @Test
    fun `comparison operators cover negative outcomes and malformed expected values`() = runTest {
        suspend fun evaluate(operator: AttrOperator, actual: String, expected: JsonElement): Boolean {
            val condition = Condition.DeviceAttribute("version", operator, expected)
            return evaluator().evaluateCondition(
                condition,
                EvaluationContext(UUID.random(), "i", testDevice(version = actual), null),
            )
        }

        assertTrue(evaluate(AttrOperator.NOT_EQUALS, "1", JsonPrimitive("2")))
        assertFalse(evaluate(AttrOperator.IN, "3", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2")))))
        assertTrue(evaluate(AttrOperator.NOT_IN, "3", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2")))))
        assertFalse(evaluate(AttrOperator.CONTAINS, "Android", JsonPrimitive("iOS")))
        assertFalse(evaluate(AttrOperator.STARTS_WITH, "Android", JsonPrimitive("iOS")))
        assertFalse(evaluate(AttrOperator.ENDS_WITH, "Android", JsonPrimitive("iOS")))
        assertFalse(evaluate(AttrOperator.STARTS_WITH, "Android", JsonNull))
        assertFalse(evaluate(AttrOperator.ENDS_WITH, "Android", JsonNull))
        assertFalse(evaluate(AttrOperator.GREATER_THAN, "1", JsonPrimitive(2)))
        assertFalse(evaluate(AttrOperator.LESS_THAN, "2", JsonPrimitive(1)))
        assertFalse(evaluate(AttrOperator.GTE, "1", JsonPrimitive(2)))
        assertFalse(evaluate(AttrOperator.LTE, "2", JsonPrimitive(1)))
        assertFalse(evaluate(AttrOperator.GREATER_THAN, "2", buildJsonObject { put("n", 1) }))
        assertFalse(evaluate(AttrOperator.CONTAINS, "2", JsonArray(emptyList())))
        assertFalse(
            evaluate(
                AttrOperator.IN,
                "2",
                JsonArray(listOf(buildJsonObject { put("n", 2) }, JsonNull)),
            ),
        )
    }

    @Test
    fun `dotted version comparison handles missing and non-numeric segments`() = runTest {
        suspend fun evaluate(operator: AttrOperator, actual: String, expected: String): Boolean {
            val condition = Condition.DeviceAttribute("version", operator, JsonPrimitive(expected))
            return evaluator().evaluateCondition(
                condition,
                EvaluationContext(UUID.random(), "i", testDevice(version = actual), null),
            )
        }

        assertTrue(evaluate(AttrOperator.SEMVER_LT, "1.beta", "1.0.1"))
        assertTrue(evaluate(AttrOperator.SEMVER_GT, "1.0.1", "1.beta"))
        assertTrue(evaluate(AttrOperator.SEMVER_LTE, "1.0", "1.0.0"))
    }

    @Test
    fun `evaluation context identifier remains installation based after login`() {
        val principalId = UUID.random()
        assertEquals(
            "installation",
            EvaluationContext(principalId, "installation", null, null).identifier,
        )
        assertEquals(
            "installation",
            EvaluationContext(null, "installation", null, null).identifier,
        )
    }

    @Test
    fun `remaining condition branches reject authenticated non-members and malformed values`() = runTest {
        val principalId = UUID.random()
        val segmentId = UUID.random()
        val context = EvaluationContext(
            principalId,
            "installation",
            testDevice(version = "2.0"),
            null,
            profileAttributes = listOf(
                ProfileAttribute(
                    profile = UUID.random(),
                    typeId = "different-type",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 0,
                    source = "test",
                    attributes = buildJsonObject { put("country", "US") },
                ),
            ),
            memberSegmentIds = emptySet(),
        )

        assertFalse(evaluator().evaluateCondition(Condition.Segment(segmentId), context))
        assertFalse(
            evaluator().evaluateCondition(
                Condition.ProfileAttribute("wanted-type", "country", AttrOperator.EQUALS, JsonPrimitive("US")),
                context,
            ),
        )
        assertFalse(
            evaluator().evaluateCondition(
                Condition.DeviceAttribute("version", AttrOperator.GREATER_THAN, JsonNull),
                context,
            ),
        )
        assertFalse(
            evaluator().evaluateCondition(
                Condition.DeviceAttribute("version", AttrOperator.SEMVER_LT, JsonPrimitive("1.0")),
                context,
            ),
        )
        assertFalse(
            evaluator().evaluateCondition(
                Condition.DeviceAttribute("version", AttrOperator.SEMVER_GTE, JsonPrimitive("3.0")),
                context,
            ),
        )
    }
}
