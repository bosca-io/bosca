package bosca.experimentation.service

import bosca.analytics.model.Device
import bosca.experimentation.model.AttrOperator
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.Condition
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagEvaluation
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.Variation
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.profile.service.ProfileService
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.slf4j.LoggerFactory

/**
 * Per-evaluation context that carries the raw request inputs and memoized caches.
 * Profile attributes and segment memberships are loaded lazily on first access and
 * cached for the duration of a single evaluation (or single evaluateAll batch) so
 * a flag with multiple attribute conditions doesn't issue a query per condition.
 * The installation ID is required because every evaluation must have a stable subject
 * for bucketing and current-assignment persistence, including anonymous requests.
 *
 * **Threading contract:** This class is NOT thread-safe. All mutable fields
 * (`cachedProfileAttributes`, `cachedMemberSegmentIds`, `visitedFlags`, `degraded`)
 * assume single-coroutine access. Callers must NOT share an instance across
 * concurrent coroutines (e.g. `async {}` blocks). The `evaluateAll` path in
 * [FeatureFlagServiceImpl] uses sequential `mapNotNull`, which is safe.
 */
private val evalContextLog = LoggerFactory.getLogger("bosca.experimentation.service.EvaluationContext")

internal class EvaluationContext(
    val principalId: UUID?,
    val installationId: String,
    /**
     * Typed device snapshot supplied by the caller of `evaluate`. Targeting
     * rule [Condition.DeviceAttribute] conditions resolve their `key` against
     * this object's fields via [Device.attributeValue]. Null when the caller
     * provided no device context (server-to-server calls, tests, etc.).
     */
    val device: Device?,
    private val profileService: ProfileService?,
    memberSegmentIds: Set<UUID>? = null,
    profileAttributes: List<ProfileAttribute>? = null,
) {
    private var cachedProfileAttributes: List<ProfileAttribute>? = profileAttributes
    private var cachedMemberSegmentIds: Set<UUID>? = memberSegmentIds

    /**
     * Set to `true` when a profile or segment lookup fails and the evaluation
     * proceeds with incomplete data. Callers can inspect this after evaluation
     * to surface the degradation to downstream consumers (e.g. a `degraded`
     * field on the GraphQL response).
     */
    var degraded: Boolean = false
        internal set

    /** Marks this context as degraded from an external preload failure. */
    fun markDegraded() { degraded = true }

    /**
     * Flag keys currently in the middle of evaluation on this context,
     * used for [Condition.FlagDependency] cycle detection. The set is
     * intentionally mutable and shared across the recursive evaluation
     * so profile attributes and segment memberships fetched during a
     * parent's evaluation carry through to the prerequisite evaluation
     * without a second network hop.
     */
    val visitedFlags: MutableSet<String> = HashSet()

    /**
     * Stable rollout identifier for this installation. Authentication must not
     * change the bucket after an anonymous visitor logs in; [principalId] is
     * still available separately for principal, profile, and segment targeting.
     */
    val identifier: String
        get() = installationId

    suspend fun profileAttributes(): List<ProfileAttribute> {
        if (cachedProfileAttributes != null) return cachedProfileAttributes!!
        val pid = principalId
        val svc = profileService
        if (pid == null || svc == null) {
            cachedProfileAttributes = emptyList()
            return emptyList()
        }
        val profiles = try {
            svc.getByPrincipal(pid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            evalContextLog.error("Failed to load profiles for principal {} — all profile-attribute conditions will use defaults for this evaluation", pid, e)
            degraded = true
            emptyList()
        }
        val attrs = profiles.flatMap { profile ->
            try {
                svc.getAttributes(profile.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                evalContextLog.error("Failed to load attributes for profile {} — attribute conditions will use defaults for this evaluation", profile.id, e)
                degraded = true
                emptyList()
            }
        }
        cachedProfileAttributes = attrs
        return attrs
    }

    suspend fun memberSegmentIds(segmentService: SegmentService): Set<UUID> {
        cachedMemberSegmentIds?.let { return it }
        val pid = principalId
        val svc = profileService
        val set = if (pid == null || svc == null) {
            emptySet()
        } else {
            // segmentService.getSegmentsByProfileId takes a *profile* id, not a
            // principal id. A principal can have multiple profiles, so resolve
            // them first and union the segments across all of them.
            try {
                val profiles = svc.getByPrincipal(pid)
                profiles.flatMap { profile ->
                    segmentService.getSegmentsByProfileId(profile.id).map { it.id }
                }.toSet()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                evalContextLog.error("Failed to load segments for principal {} — all segment conditions will use defaults for this evaluation", pid, e)
                degraded = true
                emptySet()
            }
        }
        cachedMemberSegmentIds = set
        return set
    }
}

/**
 * Stateless evaluator that resolves a [FeatureFlag] plus an [EvaluationContext] into a
 * [FlagEvaluation]. Extracted from [FeatureFlagServiceImpl] so the evaluation logic —
 * rule matching, condition evaluation, bucketing, and all comparison operators — is
 * independently testable without the service's cache, pub-sub, and repository machinery.
 *
 * The evaluator delegates to external callbacks for side-effects that the evaluation logic
 * needs but should not own: looking up flags by key (for [Condition.FlagDependency]),
 * finding running experiments, checking exclusion-layer assignments, recording assignments,
 * and recording current feature-flag assignments.
 */
internal class FlagEvaluator(
    private val segmentService: SegmentService,
    private val json: Json,
    /** Resolves a flag by its key (used for FlagDependency prerequisite evaluation). */
    private val flagLookup: suspend (String) -> FeatureFlag?,
    /** Returns the running experiment attached to a (flag, rule) pair, if any. */
    private val findRunningExperiment: suspend (flagId: UUID, ruleId: String?) -> Experiment?,
    /** Checks whether a user is already assigned to another experiment in the same layer. */
    private val isExcludedFromLayer: suspend (layerId: UUID, experimentId: UUID, principalId: UUID?, installationId: String) -> Boolean,
    /** Persists or resolves the stable assignment; returns null when it cannot be recorded. */
    private val recordAssignment: suspend (experimentId: UUID, flagKey: String, principalId: UUID?, installationId: String) -> Assignment?,
    /** Best-effort current assignment recording. */
    private val recordFlagAssignment: (
        flag: FeatureFlag,
        ruleId: String?,
        principalId: UUID?,
        installationId: String,
        device: Device?,
        variationKey: String,
    ) -> Unit,
) {
    /**
     * Core evaluation logic. Walks the rules; for the first rule whose conditions all
     * match, buckets the user across the rule's rollout and returns the chosen variation.
     * If no rule matches, returns the flag's default variation.
     */
    suspend fun evaluateFlag(flag: FeatureFlag, ctx: EvaluationContext): FlagEvaluation {
        val variations = parseVariations(flag.variations)
        val defaultEvaluation = defaultEvaluation(flag, variations, ctx.degraded)

        if (flag.status != FlagStatus.ENABLED) return defaultEvaluation

        val rules = parseTargetingRules(flag.targetingRules)
        for (rule in rules) {
            if (!rule.conditions.all { evaluateCondition(it, ctx) }) continue

            val attachedExperiment = findRunningExperiment(flag.id, rule.id)
            val attachedExperimentId = attachedExperiment?.id
            val excludedByLayer = attachedExperiment?.exclusionLayerId?.let { layerId ->
                isExcludedFromLayer(layerId, attachedExperiment.id, ctx.principalId, ctx.installationId)
            } ?: false
            if (excludedByLayer) {
                log.debug(
                    "Skipping rule {} on flag {} for user {} — already assigned to another experiment in layer {}",
                    rule.id, flag.id, ctx.identifier, attachedExperiment.exclusionLayerId,
                )
                continue
            }

            val identifier = ctx.identifier
            val bucketedVariationKey = ExperimentServiceImpl.bucketRollout(
                flag.key, flag.salt, rule.id, rule.rollout, identifier
            )
            val assignment = if (attachedExperimentId != null) {
                recordAssignment(attachedExperimentId, flag.key, ctx.principalId, ctx.installationId)
            } else {
                null
            }
            val assignedVariation = assignment
                ?.takeIf { it.experimentId == attachedExperimentId }
                ?.let { persisted -> variations.find { it.key == persisted.variationKey } }
            val variation = assignedVariation
                ?: variations.find { it.key == bucketedVariationKey }
                ?: continue
            val assignmentRecorded = assignedVariation != null
            if (attachedExperimentId != null && !assignmentRecorded) {
                log.warn(
                    "Dropping experiment id {} from evaluate response for flag {} user {} — " +
                        "assignment write failed or its variation is no longer valid",
                    attachedExperimentId, flag.key, ctx.identifier,
                )
            }
            val effectiveExperimentId =
                if (attachedExperimentId != null && assignmentRecorded) attachedExperimentId else null
            recordFlagAssignment(
                flag,
                rule.id,
                ctx.principalId,
                ctx.installationId,
                ctx.device,
                variation.key,
            )
            return FlagEvaluation(
                flagKey = flag.key,
                variationKey = variation.key,
                value = variation.value,
                experimentId = effectiveExperimentId,
                degraded = ctx.degraded,
            )
        }

        // No rule matched — check the default-variation path for a running experiment.
        val identifier = ctx.identifier
        val defaultExperiment = findRunningExperiment(flag.id, null)
        val defaultExperimentId = defaultExperiment?.id
        val excludedFromDefaultExperiment = defaultExperiment?.exclusionLayerId?.let { layerId ->
            isExcludedFromLayer(layerId, defaultExperiment.id, ctx.principalId, ctx.installationId)
        } ?: false
        val candidateExperimentId = if (excludedFromDefaultExperiment) null else defaultExperimentId
        val effectiveExperimentId = if (candidateExperimentId != null) {
            val assignment = recordAssignment(
                candidateExperimentId,
                flag.key,
                ctx.principalId,
                ctx.installationId,
            )
            if (
                assignment?.experimentId == candidateExperimentId &&
                assignment.variationKey == defaultEvaluation.variationKey
            ) {
                candidateExperimentId
            } else {
                log.warn(
                    "Dropping default-path experiment id {} from evaluate response for flag {} user {} — " +
                        "assignment write failed or does not match the default variation",
                    candidateExperimentId, flag.key, identifier,
                )
                null
            }
        } else null
        recordFlagAssignment(
            flag,
            null,
            ctx.principalId,
            ctx.installationId,
            ctx.device,
            defaultEvaluation.variationKey,
        )
        if (effectiveExperimentId != null) {
            return defaultEvaluation.copy(experimentId = effectiveExperimentId)
        }

        return defaultEvaluation
    }

    /**
     * Resolves only the declared default variation, without consulting targeting,
     * experiments, or other services. The service boundary uses this to fail closed
     * when ordinary evaluation is unavailable while preserving the flag's value type.
     */
    internal fun defaultEvaluation(flag: FeatureFlag, degraded: Boolean): FlagEvaluation {
        val variations = parseVariations(flag.variations)
        return defaultEvaluation(flag, variations, degraded)
    }

    private fun defaultEvaluation(
        flag: FeatureFlag,
        variations: List<Variation>,
        degraded: Boolean,
    ): FlagEvaluation {
        val defaultVariation = variations.find { it.key == flag.defaultVariationKey }
            ?: variations.firstOrNull()
        return if (defaultVariation != null) {
            FlagEvaluation(flagKey = flag.key, variationKey = defaultVariation.key, value = defaultVariation.value, degraded = degraded)
        } else {
            FlagEvaluation(flagKey = flag.key, variationKey = "", value = JsonNull, degraded = degraded)
        }
    }

    /**
     * Evaluates a single condition, honoring [Condition.negate] uniformly.
     */
    internal suspend fun evaluateCondition(condition: Condition, ctx: EvaluationContext): Boolean {
        val raw = when (condition) {
            is Condition.Segment -> {
                ctx.principalId != null &&
                        ctx.memberSegmentIds(segmentService).contains(condition.segmentId)
            }

            is Condition.Principal -> {
                ctx.principalId != null && ctx.principalId in condition.principalIds
            }

            is Condition.DeviceAttribute -> {
                val device = ctx.device ?: return condition.negate
                val actual = device.attributeValue(condition.key) ?: return condition.negate
                compareAttribute(actual, condition.operator, condition.value)
            }

            is Condition.ProfileAttribute -> {
                val attributes = ctx.profileAttributes()
                val attrForType = attributes.find { it.typeId == condition.typeId }
                val attrJson = attrForType?.attributes
                val actual = extractStringFromJson(attrJson, condition.key) ?: return condition.negate
                compareAttribute(actual, condition.operator, condition.value)
            }

            is Condition.FlagDependency -> {
                if (condition.flagKey in ctx.visitedFlags) {
                    log.error(
                        "FlagDependency cycle detected while evaluating prerequisite chain: {} → already visited",
                        ctx.visitedFlags.joinToString(" → ") + " → ${condition.flagKey}",
                    )
                    return condition.negate
                }
                val dependent = flagLookup(condition.flagKey)
                if (dependent == null || dependent.status != FlagStatus.ENABLED) {
                    return condition.negate
                }
                ctx.visitedFlags.add(condition.flagKey)
                val resolved = try {
                    evaluateFlag(dependent, ctx)
                } finally {
                    ctx.visitedFlags.remove(condition.flagKey)
                }
                resolved.variationKey == condition.requiredVariationKey
            }
        }
        return if (condition.negate) !raw else raw
    }

    // -----------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------

    internal fun parseVariations(variationsJson: JsonElement): List<Variation> =
        json.decodeFromJsonElement(ListSerializer(Variation.serializer()), variationsJson)

    internal fun parseTargetingRules(rulesJson: JsonElement?): List<TargetingRule> {
        if (rulesJson == null) return emptyList()
        return json.decodeFromJsonElement(ListSerializer(TargetingRule.serializer()), rulesJson)
    }

    // -----------------------------------------------------------------
    // Comparison operators
    // -----------------------------------------------------------------

    private fun extractStringFromJson(element: JsonElement?, key: String): String? {
        if (element !is JsonObject) return null
        val v = element[key] ?: return null
        return (v as? JsonPrimitive)?.contentOrNull
    }

    private fun compareAttribute(actual: String, operator: AttrOperator, expected: JsonElement): Boolean {
        return when (operator) {
            AttrOperator.EQUALS -> actual == expectedString(expected)
            AttrOperator.NOT_EQUALS -> actual != expectedString(expected)
            AttrOperator.IN -> expectedList(expected).any { it == actual }
            AttrOperator.NOT_IN -> expectedList(expected).none { it == actual }
            AttrOperator.CONTAINS -> expectedString(expected)?.let { actual.contains(it) } ?: false
            AttrOperator.STARTS_WITH -> expectedString(expected)?.let { actual.startsWith(it) } ?: false
            AttrOperator.ENDS_WITH -> expectedString(expected)?.let { actual.endsWith(it) } ?: false
            AttrOperator.GREATER_THAN -> numericCompare(actual, expected) { a, e -> a > e }
            AttrOperator.LESS_THAN -> numericCompare(actual, expected) { a, e -> a < e }
            AttrOperator.GTE -> numericCompare(actual, expected) { a, e -> a >= e }
            AttrOperator.LTE -> numericCompare(actual, expected) { a, e -> a <= e }
            AttrOperator.SEMVER_GT -> semverCompare(actual, expected) { it > 0 }
            AttrOperator.SEMVER_LT -> semverCompare(actual, expected) { it < 0 }
            AttrOperator.SEMVER_GTE -> semverCompare(actual, expected) { it >= 0 }
            AttrOperator.SEMVER_LTE -> semverCompare(actual, expected) { it <= 0 }
        }
    }

    private fun expectedString(expected: JsonElement): String? {
        return (expected as? JsonPrimitive)?.contentOrNull
    }

    private fun expectedList(expected: JsonElement): List<String> {
        if (expected !is JsonArray) return emptyList()
        return expected.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private inline fun numericCompare(
        actual: String,
        expected: JsonElement,
        compare: (Double, Double) -> Boolean
    ): Boolean {
        val a = actual.toDoubleOrNull() ?: return false
        val e = when (expected) {
            is JsonPrimitive -> expected.doubleOrNull ?: expected.contentOrNull?.toDoubleOrNull()
            else -> null
        } ?: return false
        return compare(a, e)
    }

    private inline fun semverCompare(
        actual: String,
        expected: JsonElement,
        test: (Int) -> Boolean
    ): Boolean {
        val e = expectedString(expected) ?: return false
        return test(compareDottedVersion(actual, e))
    }

    /**
     * Compares two dotted version strings numerically, segment by
     * segment. NOT strict semver: pre-release identifiers after `-` are
     * split on the same delimiter and treated as additional numeric
     * segments, so `1.0.0-beta.1` compares as `[1,0,0,0,1]` and sorts
     * *greater* than `1.0.0`, which is the opposite of the semver spec.
     *
     * This is intentional for the flag-targeting use case — operators
     * compare app version strings like `2.14.3` against
     * `SEMVER_GTE 2.14.0`, and treating pre-release tags as "lower" would
     * silently exclude beta testers from rules the operator expects to
     * match them.
     */
    private fun compareDottedVersion(a: String, b: String): Int {
        val aParts = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val bParts = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val len = maxOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val ap = if (i < aParts.size) aParts[i] else 0
            val bp = if (i < bParts.size) bParts[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }
        return 0
    }

    companion object {
        private val log = LoggerFactory.getLogger(FlagEvaluator::class.java)
    }
}
