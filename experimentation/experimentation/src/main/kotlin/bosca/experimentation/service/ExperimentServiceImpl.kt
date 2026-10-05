package bosca.experimentation.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.observability.ErrorCapture
import bosca.db.transaction
import bosca.db.afterCommit
import bosca.experimentation.isValidItemExtraKey
import bosca.experimentation.events.ExperimentVariationAssigned
import bosca.experimentation.events.dispatch
import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalInput
import bosca.experimentation.model.EXPERIMENT_UPDATED_CHANNEL
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.ExperimentInput
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.ExperimentUpdateAction
import bosca.experimentation.model.ExperimentUpdated
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.Variation
import bosca.pubsub.PubSubService
import bosca.experimentation.repository.AnalysisReportRepository
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ConversionGoalRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.experimentation.repository.ExperimentResultRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory
import bosca.sharedqueue.jobs.enqueueLater
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.minutes

/**
 * Implementation of [ExperimentService] for the variation-based model.
 *
 * Experiments do not own values or variants. They are observation+stats attached to
 * a single targeting rule on a feature flag. When a user is assigned, the assignment
 * stores the variation key the user was bucketed into; that variation key references
 * a [bosca.experimentation.model.Variation] in the parent flag's variations palette.
 *
 * Bucketing for both flag rollouts and experiment exposures uses the same hash:
 *   stableHash("$flagKey:$salt:$ruleId:$identifier") % totalWeight
 *
 * Including the rule id (instead of an array index) keeps assignments stable across
 * rule reorderings, and including the per-flag salt provides the manual reshuffle escape.
 */
@ServiceImplementation
class ExperimentServiceImpl(
    private val experimentRepository: ExperimentRepository,
    private val assignmentRepository: AssignmentRepository,
    private val conversionGoalRepository: ConversionGoalRepository,
    private val experimentResultRepository: ExperimentResultRepository,
    private val analysisReportRepository: AnalysisReportRepository,
    private val featureFlagRepository: FeatureFlagRepository,
    private val rolloutPolicyEventRepository: bosca.experimentation.repository.RolloutPolicyEventRepository,
    private val pubSubService: PubSubService,
    private val json: Json,
    private val errorCapture: ErrorCapture,
) : ExperimentService {

    private val log = LoggerFactory.getLogger(ExperimentServiceImpl::class.java)

    /**
     * Assignment lookups sit directly on the feature-evaluation hot path. The
     * cache key includes every input that affects identity resolution so an
     * anonymous lookup and a later authenticated lookup cannot share a stale
     * negative entry. Assignment variation is immutable; the only mutation is
     * attaching a principal to an anonymous installation, and that path
     * overwrites both relevant cache keys after the database transaction.
     */
    private val assignmentCache = ServiceCache<String, Assignment>(
        cacheName = "experimentation:assignment:by-identity",
        serializer = StringKeySerializer,
        expiration = ASSIGNMENT_CACHE_TTL,
    ) { key ->
        val parts = key.split(':', limit = 3)
        require(parts.size == 3) { "Invalid assignment cache key" }
        assignmentRepository.getByIdentity(
            experimentId = UUID.parse(parts[0]),
            principalId = parts[1].takeIf(String::isNotEmpty)?.let(UUID::parse),
            installationId = parts[2],
        )
    }

    override suspend fun getAll(offset: Long, limit: Int): List<Experiment> {
        return experimentRepository.getAll(offset, limit)
    }

    override suspend fun getById(id: UUID): Experiment? {
        return experimentRepository.getById(id)
    }

    override suspend fun getByFlagId(flagId: UUID): List<Experiment> {
        return experimentRepository.getByFlagId(flagId)
    }

    override suspend fun getByFlagId(flagId: UUID, offset: Long, limit: Int): List<Experiment> {
        return experimentRepository.getByFlagId(flagId, offset, limit)
    }

    override suspend fun add(input: ExperimentInput): Experiment {
        val activationFilter = validateAndNormalizeActivationFilter(input.activationFilter)
        val saved = transaction {
            val flag = featureFlagRepository.getById(input.featureFlagId)
                ?: error("Feature flag not found: ${input.featureFlagId}")
            validateRuleAttachment(flag, input.targetingRuleId)
            validateControlVariation(flag, input.targetingRuleId, input.controlVariationKey)
            // Fail loudly if the policy references a variation the
            // attached rule doesn't actually serve. Silently promoting
            // a non-existent variation would leave the controller
            // spinning on every analysis wake.
            input.rolloutPolicy?.let {
                validatePolicyAgainstFlag(it, flag, input.targetingRuleId, input.controlVariationKey)
            }
            val experiment = Experiment(
                featureFlagId = input.featureFlagId,
                name = input.name,
                description = input.description ?: "",
                hypothesis = input.hypothesis ?: "",
                targetingRuleId = input.targetingRuleId,
                controlVariationKey = input.controlVariationKey,
                excludedPrincipalIds = input.excludedPrincipalIds.orEmpty().distinct(),
                activationFilter = activationFilter,
                exclusionLayerId = input.exclusionLayerId,
                startDate = input.startDate,
                endDate = input.endDate,
                targetSampleSize = input.targetSampleSize,
                rolloutPolicy = input.rolloutPolicy?.let {
                    json.encodeToJsonElement(RolloutPolicy.serializer(), it)
                },
                analysisMethod = input.analysisMethod,
                bayesianPrior = input.bayesianPrior?.let {
                    json.encodeToJsonElement(BayesianPrior.serializer(), it)
                },
            )
            experimentRepository.add(experiment)
        }
        // New experiments are always created in DRAFT, so the
        // running-experiment cache cannot have a stale entry for this
        // (flag, rule) pair yet — but publishing on create still costs
        // nothing and lets other subscribers (admin UI live updates,
        // dashboards) react. Cache invalidation is uniform across
        // actions on the consumer side.
        publishExperimentUpdate(saved.id, saved.featureFlagId, ExperimentUpdateAction.CREATED)
        return saved
    }

    override suspend fun edit(id: UUID, input: ExperimentInput): Experiment {
        val activationFilter = validateAndNormalizeActivationFilter(input.activationFilter)
        val (updated, changed) = transaction {
            val existing = experimentRepository.getById(id) ?: error("Experiment not found: $id")
            require(input.featureFlagId == existing.featureFlagId) {
                "Cannot change the linked feature flag of an existing experiment"
            }
            // Once an experiment leaves DRAFT, the rule attachment is locked — changing it
            // would silently invalidate accumulated assignments.
            if (existing.status != ExperimentStatus.DRAFT) {
                require(input.targetingRuleId == existing.targetingRuleId) {
                    "Cannot change the targeting rule attachment after the experiment has left DRAFT"
                }
                require(input.controlVariationKey == existing.controlVariationKey) {
                    "Cannot change the control variation after the experiment has left DRAFT"
                }
            }
            val flag = featureFlagRepository.getById(input.featureFlagId)
                ?: error("Feature flag not found: ${input.featureFlagId}")
            val controlChanged = input.controlVariationKey != existing.controlVariationKey
            val submittedPolicy = input.rolloutPolicy
            val reconciledPolicy = if (
                controlChanged && submittedPolicy?.treatmentVariationKey == input.controlVariationKey
            ) {
                submittedPolicy.copy(treatmentVariationKey = existing.controlVariationKey)
            } else {
                submittedPolicy
            }
            validateRuleAttachment(flag, input.targetingRuleId)
            validateControlVariation(flag, input.targetingRuleId, input.controlVariationKey)
            reconciledPolicy?.let {
                validatePolicyAgainstFlag(it, flag, input.targetingRuleId, input.controlVariationKey)
            }
            val withInput = existing.copy(
                name = input.name,
                description = input.description ?: existing.description,
                hypothesis = input.hypothesis ?: existing.hypothesis,
                targetingRuleId = input.targetingRuleId,
                controlVariationKey = input.controlVariationKey,
                excludedPrincipalIds = input.excludedPrincipalIds?.distinct() ?: existing.excludedPrincipalIds,
                activationFilter = activationFilter,
                exclusionLayerId = input.exclusionLayerId,
                startDate = input.startDate,
                endDate = input.endDate,
                targetSampleSize = input.targetSampleSize,
                rolloutPolicy = reconciledPolicy?.let {
                    json.encodeToJsonElement(RolloutPolicy.serializer(), it)
                },
                analysisMethod = input.analysisMethod,
                bayesianPrior = input.bayesianPrior?.let {
                    json.encodeToJsonElement(BayesianPrior.serializer(), it)
                },
            )
            if (withInput == existing) {
                existing to false
            } else {
                experimentRepository.update(withInput).also {
                    if (controlChanged || withInput.excludedPrincipalIds.toSet() != existing.excludedPrincipalIds.toSet() ||
                        withInput.activationFilter != existing.activationFilter
                    ) {
                        experimentResultRepository.deleteByExperimentId(id)
                    }
                } to true
            }
        }
        if (changed) {
            publishExperimentUpdate(updated.id, updated.featureFlagId, ExperimentUpdateAction.UPDATED)
        }
        return updated
    }

    override suspend fun delete(id: UUID) {
        // Look up the flag id BEFORE the delete so the invalidation
        // event can carry it. After the delete, the experiment row is
        // gone and we'd have nothing to publish.
        val existing = experimentRepository.getById(id)
        experimentRepository.deleteById(id)
        if (existing != null) {
            publishExperimentUpdate(existing.id, existing.featureFlagId, ExperimentUpdateAction.DELETED)
        }
    }

    override suspend fun setStatus(id: UUID, status: ExperimentStatus): Experiment {
        val experiment = experimentRepository.getById(id)
            ?: error("Experiment not found: $id")
        validateStatusTransition(experiment, status)
        val updated = experimentRepository.updateStatus(id, status)
            ?: error("Experiment not found: $id")
        // Status transitions are the load-bearing event for the
        // running-experiment cache: a DRAFT→RUNNING transition adds an
        // entry that didn't exist, and any transition out of RUNNING
        // removes one that did. Publishing on every status change keeps
        // the cache honest without having to special-case which
        // transitions matter.
        publishExperimentUpdate(updated.id, updated.featureFlagId, ExperimentUpdateAction.STATUS_CHANGED)

        // Phase 3 — scheduled rollout ramps. A DRAFT/PAUSED → RUNNING
        // transition on a SCHEDULED_STEPS policy kicks off the first
        // scheduled-step wake at now + steps[0].afterDuration. The
        // executor re-reads status on wake, so operators pausing the
        // experiment in the middle of the ramp do not require
        // cancelling queued jobs — the next wake no-ops.
        if (status == ExperimentStatus.RUNNING) {
            parsePolicy(updated.rolloutPolicy)
                ?.takeIf { it.mode == RolloutPolicyMode.SCHEDULED_STEPS }
                ?.let { policy ->
                    // Fail the mutation loudly when the first scheduled step
                    // cannot be enqueued. An experiment that cannot start its
                    // rollout chain must not be marked RUNNING — otherwise the
                    // operator sees "RUNNING" and believes the gradual rollout
                    // is proceeding while it is permanently stuck at initial weight.
                    enqueueFirstScheduledStep(updated.id, policy)
                }
        }

        return updated
    }

    /**
     * Parses the serialized [RolloutPolicy] on [Experiment.rolloutPolicy].
     * A malformed blob throws — broken policies are data-integrity
     * bugs that must surface, not be swallowed into "no policy
     * here" ghost behavior.
     */
    private fun parsePolicy(element: JsonElement?): RolloutPolicy? {
        if (element == null) return null
        return json.decodeFromJsonElement(RolloutPolicy.serializer(), element)
    }

    /**
     * Validates a policy against the flag it will operate on.
     * Specifically: the `treatmentVariationKey` must reference a
     * variation actually served by the attached rule's rollout (or
     * the flag's default rollout when `targetingRuleId == null`).
     * Catches misconfigured policies at write time instead of letting
     * the controller spin forever looking for a variation that does
     * not exist.
     */
    private fun validatePolicyAgainstFlag(
        policy: RolloutPolicy,
        flag: FeatureFlag,
        targetingRuleId: String?,
        controlVariationKey: String,
    ) {
        val served = involvedVariationKeys(flag, targetingRuleId)
        require(policy.treatmentVariationKey in served) {
            "Rollout policy treatmentVariationKey '${policy.treatmentVariationKey}' " +
                "is not served by the attached rule (served: $served)"
        }
        require(policy.treatmentVariationKey != controlVariationKey) {
            "Rollout policy treatmentVariationKey must differ from controlVariationKey"
        }
    }

    private fun validateControlVariation(flag: FeatureFlag, targetingRuleId: String?, controlVariationKey: String) {
        require(controlVariationKey.isNotBlank()) { "controlVariationKey is required" }
        val palette = json.decodeFromJsonElement(ListSerializer(Variation.serializer()), flag.variations)
            .map { it.key }
            .toSet()
        require(controlVariationKey in palette) {
            "Control variation '$controlVariationKey' does not exist on flag '${flag.key}'"
        }
        val involved = involvedVariationKeys(flag, targetingRuleId)
        require(controlVariationKey in involved) {
            "Control variation '$controlVariationKey' is not involved in the experiment (involved: $involved)"
        }
    }

    private fun involvedVariationKeys(flag: FeatureFlag, targetingRuleId: String?): Set<String> {
        if (targetingRuleId == null) {
            return json.decodeFromJsonElement(ListSerializer(Variation.serializer()), flag.variations)
                .mapTo(linkedSetOf()) { it.key }
        }
        val rule = findAttachedRule(flag, targetingRuleId)
            ?: error("Targeting rule '$targetingRuleId' does not exist on flag '${flag.key}'")
        return rule.rollout.variationWeights.mapTo(linkedSetOf()) { it.variationKey }
    }

    /**
     * Enqueues the first scheduled-step rollout job after DRAFT →
     * RUNNING. The ISO-8601 `afterDuration` on the first step
     * determines the delay. Subsequent steps are chained by
     * [RolloutPolicyJobExecutor] itself after each Advance.
     */
    private suspend fun enqueueFirstScheduledStep(experimentId: UUID, policy: RolloutPolicy) {
        val steps = policy.steps ?: return
        if (steps.isEmpty()) return
        val afterIso = steps[0].afterDuration ?: return
        val delay = try {
            val jd = java.time.Duration.parse(afterIso)
            kotlin.time.Duration.parse("${jd.toMillis()}ms")
        } catch (e: Exception) {
            error(
                "Invalid afterDuration '$afterIso' on experiment $experimentId step 0: ${e.message}",
            )
        }
        val jobQueue = try {
            bosca.di.provide<bosca.sharedqueue.jobs.JobQueue>(
                bosca.experimentation.configuration.JobQueueNames.experimentationJobQueue
            )
        } catch (e: Exception) {
            error("Experimentation job queue not available for scheduled step enqueue: ${e.message}")
        }
        bosca.experimentation.jobs.RolloutPolicyJob(
            experimentId = experimentId,
            scheduledStepIndex = 0,
        ).enqueueLater(
            queue = jobQueue,
            executor = bosca.experimentation.jobs.RolloutPolicyJobExecutor::class,
            timeout = delay,
        )
    }

    /**
     * Publishes an experiment-updated event so cache subscribers (notably
     * `FeatureFlagServiceImpl`'s `runningExperimentCache`) can drop the
     * entries for this flag. Wrapped in try/catch so a transient pub/sub
     * failure cannot break the user-facing mutation — the worst case is
     * a brief window of stale cache that the next status change (or the
     * 10-minute TTL) will resolve.
     */
    private suspend fun publishExperimentUpdate(
        experimentId: UUID,
        flagId: UUID,
        action: ExperimentUpdateAction,
    ) {
        afterCommit {
            try {
                pubSubService.publish(
                    EXPERIMENT_UPDATED_CHANNEL,
                    ExperimentUpdated.serializer(),
                    ExperimentUpdated(experimentId = experimentId, flagId = flagId, action = action),
                )
            } catch (e: Exception) {
                log.error("Failed to publish experiment update for {} (flag {})", experimentId, flagId, e)
                errorCapture.capture(e, null, mapOf("experimentId" to experimentId.toString(), "flagId" to flagId.toString(), "action" to action.name))
            }
        }
    }

    /**
     * Enforces valid lifecycle transitions. To enter RUNNING, the attached rule's
     * rollout must split across at least 2 distinct variations — otherwise there's
     * nothing to compare and the experiment is meaningless.
     */
    private suspend fun validateStatusTransition(experiment: Experiment, newStatus: ExperimentStatus) {
        val allowed = when (experiment.status) {
            ExperimentStatus.DRAFT -> setOf(ExperimentStatus.RUNNING)
            ExperimentStatus.RUNNING -> setOf(ExperimentStatus.PAUSED, ExperimentStatus.COMPLETED)
            ExperimentStatus.PAUSED -> setOf(ExperimentStatus.RUNNING, ExperimentStatus.COMPLETED)
            ExperimentStatus.COMPLETED -> setOf(ExperimentStatus.ARCHIVED)
            ExperimentStatus.ARCHIVED -> emptySet()
        }
        require(newStatus in allowed) {
            "Invalid status transition from ${experiment.status} to $newStatus"
        }
        if (newStatus == ExperimentStatus.RUNNING) {
            require(experiment.targetingRuleId != null) {
                "Cannot start an experiment without a targeting rule because the default path has no allocation rollout"
            }
            val flag = featureFlagRepository.getById(experiment.featureFlagId)
                ?: error("Feature flag not found: ${experiment.featureFlagId}")
            val distinctVariations = involvedVariationKeys(flag, experiment.targetingRuleId)
            require(distinctVariations.size >= 2) {
                "Cannot start an experiment whose attached rule serves only one variation — add at least one more to the rollout"
            }
            validateControlVariation(flag, experiment.targetingRuleId, experiment.controlVariationKey)
            parsePolicy(experiment.rolloutPolicy)?.let {
                validatePolicyAgainstFlag(it, flag, experiment.targetingRuleId, experiment.controlVariationKey)
            }
        }
    }

    /**
     * Verifies that the targeting rule the experiment claims to attach to actually
     * exists in the flag's rules JSON. A null id is allowed — it means the
     * experiment observes the flag's default-variation path.
     */
    private fun validateRuleAttachment(flag: FeatureFlag, targetingRuleId: String?) {
        if (targetingRuleId == null) return
        val rule = findAttachedRule(flag, targetingRuleId)
        require(rule != null) {
            "Targeting rule '$targetingRuleId' does not exist on flag '${flag.key}'"
        }
    }

    /**
     * Returns the [TargetingRule] in [flag] with the given id, or null if absent
     * (or if [targetingRuleId] is null, signalling the default-variation path).
     */
    private fun findAttachedRule(flag: FeatureFlag, targetingRuleId: String?): TargetingRule? {
        if (targetingRuleId == null) return null
        val rules = parseRules(flag.targetingRules) ?: return null
        return rules.find { it.id == targetingRuleId }
    }

    private fun parseRules(rulesJson: JsonElement?): List<TargetingRule>? {
        if (rulesJson == null) return null
        return json.decodeFromJsonElement(ListSerializer(TargetingRule.serializer()), rulesJson)
    }

    override suspend fun assignVariation(
        experimentId: UUID,
        principalId: UUID?,
        installationId: String,
    ): Assignment {
        require(installationId.isNotBlank()) { "installationId must not be blank" }
        val existing = getAssignment(experimentId, principalId, installationId)
        if (existing != null) {
            return claimAssignmentIfNeeded(existing, principalId, installationId)
        }

        val experiment = experimentRepository.getById(experimentId)
            ?: error("Experiment not found: $experimentId")
        val flag = featureFlagRepository.getById(experiment.featureFlagId)
            ?: error("Feature flag not found: ${experiment.featureFlagId}")
        val rule = findAttachedRule(flag, experiment.targetingRuleId)
            ?: error("Experiment is attached to a missing rule; cannot assign")

        // Match ordinary flag evaluation: authentication enriches the subject
        // but must not move an installation to a different rollout bucket.
        val identifier = installationId
        val variationKey = bucketRollout(flag.key, flag.salt, rule.id, rule.rollout, identifier)

        val assignment = transaction {
            // Defensive second line of defense for exclusion layers. Primary
            // enforcement is in `FeatureFlagServiceImpl.evaluateFlag`, which
            // skips rules whose attached experiment is in a layer the user
            // already has another assignment in. This block exists for the
            // rare case where assignVariation is called outside the evaluate
            // flow (a manual recovery script, a test) and logs a warning
            // rather than throwing — throwing here would make a user-facing
            // evaluate request fail noisily for an edge case the evaluator
            // was supposed to prevent.
            val exclusionLayerId = experiment.exclusionLayerId
            if (exclusionLayerId != null) {
                val layerAssignment = principalId?.let {
                    assignmentRepository.getByPrincipalInLayer(it, exclusionLayerId)
                } ?: assignmentRepository.getByInstallationInLayer(installationId, exclusionLayerId)
                if (layerAssignment != null && layerAssignment.experimentId != experimentId) {
                    log.warn(
                        "Skipping assignment for experiment {} — user already in experiment {} in exclusion layer {}. " +
                            "The evaluate-time exclusion check should have prevented this call.",
                        experimentId, layerAssignment.experimentId, exclusionLayerId,
                    )
                    return@transaction layerAssignment
                }
            }
            val inserted = assignmentRepository.addIfAbsent(
                experimentId,
                variationKey,
                principalId,
                installationId,
            )
            // addIfAbsent returns null when another request won either identity
            // race. Only a fresh insert is a genuinely new assignment.
            if (inserted != null) {
                ExperimentVariationAssigned(experimentId, principalId, installationId, variationKey).dispatch()
                return@transaction inserted
            }

            val raced = assignmentRepository.getByIdentity(experimentId, principalId, installationId)
                ?: error("Assignment unexpectedly missing after conflict for experiment $experimentId")
            if (principalId != null && raced.principalId == null && raced.installationId == installationId) {
                assignmentRepository.claimPrincipal(experimentId, installationId, principalId)
                    ?: assignmentRepository.getByIdentity(experimentId, principalId, installationId)
                    ?: error("Assignment unexpectedly missing after claim conflict for experiment $experimentId")
            } else {
                raced
            }
        }
        verifyAssignmentOwner(assignment, principalId, installationId)
        if (assignment.experimentId == experimentId) {
            cacheAssignment(experimentId, principalId, installationId, assignment)
        }
        return assignment
    }

    override suspend fun getAssignment(
        experimentId: UUID,
        principalId: UUID?,
        installationId: String,
    ): Assignment? {
        require(installationId.isNotBlank()) { "installationId must not be blank" }
        return assignmentCache.get(assignmentCacheKey(experimentId, principalId, installationId))
    }

    /** Claims an anonymous row without changing its variation or assignment time. */
    private suspend fun claimAssignmentIfNeeded(
        assignment: Assignment,
        principalId: UUID?,
        installationId: String,
    ): Assignment {
        verifyAssignmentOwner(assignment, principalId, installationId)
        if (
            principalId == null ||
            assignment.principalId != null ||
            assignment.installationId != installationId
        ) {
            return assignment
        }

        val claimed = transaction {
            assignmentRepository.claimPrincipal(assignment.experimentId, installationId, principalId)
                ?: assignmentRepository.getByIdentity(assignment.experimentId, principalId, installationId)
                ?: error("Assignment unexpectedly missing after claim for experiment ${assignment.experimentId}")
        }
        verifyAssignmentOwner(claimed, principalId, installationId)
        cacheAssignment(assignment.experimentId, principalId, installationId, claimed)
        return claimed
    }

    /** Rejects attempts to transfer an installation between principals. */
    private fun verifyAssignmentOwner(
        assignment: Assignment,
        principalId: UUID?,
        installationId: String,
    ) {
        if (
            assignment.installationId == installationId &&
            principalId != null &&
            assignment.principalId != null &&
            assignment.principalId != principalId
        ) {
            error(
                "Installation $installationId is already assigned to a different principal " +
                    "for experiment ${assignment.experimentId}",
            )
        }
    }

    /** Populates the requested identity and the row's canonical identity aliases. */
    private suspend fun cacheAssignment(
        experimentId: UUID,
        principalId: UUID?,
        installationId: String,
        assignment: Assignment,
    ) {
        assignmentCache.put(assignmentCacheKey(experimentId, principalId, installationId), assignment)
        assignment.installationId?.let { assignedInstallationId ->
            assignmentCache.put(assignmentCacheKey(experimentId, null, assignedInstallationId), assignment)
            assignment.principalId?.let { assignedPrincipalId ->
                assignmentCache.put(
                    assignmentCacheKey(experimentId, assignedPrincipalId, assignedInstallationId),
                    assignment,
                )
            }
        }
    }

    private fun assignmentCacheKey(
        experimentId: UUID,
        principalId: UUID?,
        installationId: String,
    ): String = "$experimentId:${principalId ?: ""}:$installationId"

    override suspend fun getConversionGoals(experimentId: UUID): List<ConversionGoal> {
        return conversionGoalRepository.getByExperimentId(experimentId)
    }

    override suspend fun getConversionGoals(experimentId: UUID, offset: Long, limit: Int): List<ConversionGoal> {
        return conversionGoalRepository.getByExperimentId(experimentId, offset, limit)
    }

    override suspend fun addConversionGoal(experimentId: UUID, input: ConversionGoalInput): ConversionGoal {
        val normalized = validateAndNormalizeGoal(input)
        val goal = ConversionGoal(
            experimentId = experimentId,
            name = normalized.name,
            eventType = normalized.eventType,
            elementType = normalized.elementType,
            elementId = normalized.elementId,
            metricType = normalized.metricType,
            pagePath = normalized.pagePath,
            pagePathPrefixes = normalized.pagePathPrefixes,
            itemExtraKey = normalized.itemExtraKey,
            itemExtraValue = normalized.itemExtraValue,
            role = normalized.role,
            cupedCovariate = normalized.cupedCovariate?.let {
                json.encodeToJsonElement(bosca.experimentation.model.CupedCovariate.serializer(), it)
            },
        )
        return transaction {
            requireMutableExperiment(experimentId)
            conversionGoalRepository.add(goal).also {
                experimentRepository.touchAnalysisRevision(experimentId)
            }
        }
    }

    override suspend fun editConversionGoal(id: UUID, input: ConversionGoalInput): ConversionGoal {
        val normalized = validateAndNormalizeGoal(input)
        return transaction {
            val existing = conversionGoalRepository.getById(id)
                ?: error("Conversion goal not found: $id")
            requireMutableExperiment(existing.experimentId)
            val updated = existing.copy(
                name = normalized.name,
                eventType = normalized.eventType,
                elementType = normalized.elementType,
                elementId = normalized.elementId,
                metricType = normalized.metricType,
                pagePath = normalized.pagePath,
                pagePathPrefixes = normalized.pagePathPrefixes,
                itemExtraKey = normalized.itemExtraKey,
                itemExtraValue = normalized.itemExtraValue,
                role = normalized.role,
                cupedCovariate = normalized.cupedCovariate?.let {
                    json.encodeToJsonElement(bosca.experimentation.model.CupedCovariate.serializer(), it)
                },
            )
            if (updated == existing) {
                existing
            } else {
                conversionGoalRepository.update(updated)
                    ?.also { experimentRepository.touchAnalysisRevision(existing.experimentId) }
                    ?: error("Conversion goal not found after update: $id")
            }
        }
    }

    override suspend fun deleteConversionGoal(id: UUID) {
        transaction {
            val goal = conversionGoalRepository.getById(id) ?: return@transaction
            requireMutableExperiment(goal.experimentId)
            conversionGoalRepository.deleteById(id)
            experimentRepository.touchAnalysisRevision(goal.experimentId)
        }
    }

    override suspend fun getResults(experimentId: UUID): List<ExperimentResult> {
        return experimentResultRepository.getByExperimentId(experimentId)
    }

    override suspend fun getResults(experimentId: UUID, offset: Long, limit: Int): List<ExperimentResult> {
        return experimentResultRepository.getByExperimentId(experimentId, offset, limit)
    }

    override suspend fun getAnalysisReports(experimentId: UUID): List<AnalysisReport> {
        return analysisReportRepository.getByExperimentId(experimentId)
    }

    override suspend fun getAnalysisReports(experimentId: UUID, offset: Long, limit: Int): List<AnalysisReport> {
        return analysisReportRepository.getByExperimentId(experimentId, offset, limit)
    }

    override suspend fun getAnalysisReportsForRevision(
        experimentId: UUID,
        controlVariationKey: String,
        analysisRevision: Long,
        offset: Long,
        limit: Int,
    ): List<AnalysisReport> {
        return analysisReportRepository.getByExperimentIdForRevision(
            experimentId,
            controlVariationKey,
            analysisRevision,
            offset,
            limit,
        )
    }

    override suspend fun getRolloutPolicyEvents(experimentId: UUID, offset: Long, limit: Int): List<RolloutPolicyEvent> {
        return rolloutPolicyEventRepository.getByExperimentId(experimentId, offset, limit)
    }

    /**
     * Save-time guard against conversion goals that would compile into an
     * unbounded Trino scan of the analytics events table.
     *
     * The aggregation job (`ExperimentResultAggregation.countConversions`)
     * also refuses to run a query whose `WHERE` clause has no narrowing
     * predicates, but failing at the runtime aggregation step means the
     * operator only finds out at job-execution time. Validating at save
     * time surfaces the error in the UI request that created the goal.
     *
     * The aggregation always pins the experiment's `start_at` (or
     * `created`) into the `WHERE` clause, so technically the query is
     * never *literally* unbounded — but a goal with no event-type, no
     * element filter, and no page-path filter is almost certainly a
     * misconfiguration. This method requires at least one narrowing
     * predicate beyond the time bound.
     */
    private fun validateAndNormalizeGoal(input: ConversionGoalInput): ConversionGoalInput {
        val itemExtraKey = input.itemExtraKey?.trim()?.takeIf { it.isNotEmpty() }
        val itemExtraValue = input.itemExtraValue
        val pagePathPrefixes = normalizePagePathPrefixes(input.pagePathPrefixes)
        require(itemExtraValue == null || itemExtraKey != null) {
            "itemExtraValue requires itemExtraKey"
        }
        if (itemExtraKey != null) {
            require(isValidItemExtraKey(itemExtraKey)) {
                "itemExtraKey must be 1–128 characters and match [a-z][a-z0-9_]*"
            }
        }
        require(itemExtraValue == null || itemExtraValue.length <= 512) {
            "itemExtraValue must be at most 512 characters"
        }
        if (input.metricType == GoalMetricType.SESSION_DURATION) {
            require(
                input.eventType == null && input.elementType.isNullOrBlank() &&
                    input.elementId.isNullOrBlank() && input.pagePath.isNullOrBlank() &&
                    pagePathPrefixes.isEmpty() && itemExtraKey == null && itemExtraValue == null &&
                    input.cupedCovariate == null
            ) {
                "SESSION_DURATION goals do not accept event, element, page, item-extra, or CUPED configuration"
            }
            return input.copy(pagePathPrefixes = emptyList(), itemExtraKey = null, itemExtraValue = null)
        }
        val hasNarrowing = input.eventType != null ||
            !input.elementType.isNullOrBlank() ||
            !input.elementId.isNullOrBlank() ||
            !input.pagePath.isNullOrBlank() ||
            pagePathPrefixes.isNotEmpty() ||
            itemExtraKey != null
        require(hasNarrowing) {
            "Conversion goal '${input.name}' must set at least one of: " +
                "eventType, elementType, elementId, pagePath, pagePathPrefixes, or itemExtraKey. " +
                "Without a narrowing predicate the aggregation job would scan every event in the warehouse."
        }
        return input.copy(
            pagePathPrefixes = pagePathPrefixes,
            itemExtraKey = itemExtraKey,
            itemExtraValue = itemExtraValue,
        )
    }

    /** Validates and normalizes the experiment-level event gate used by every outcome. */
    private fun validateAndNormalizeActivationFilter(
        filter: ExperimentActivationFilter?,
    ): ExperimentActivationFilter? {
        if (filter == null) return null
        val elementType = filter.elementType?.trim()?.takeIf(String::isNotEmpty)
        val elementId = filter.elementId?.trim()?.takeIf(String::isNotEmpty)
        val pagePath = filter.pagePath?.trim()?.takeIf(String::isNotEmpty)
        val pagePathPrefixes = normalizePagePathPrefixes(filter.pagePathPrefixes)
        val itemExtraKey = filter.itemExtraKey?.trim()?.takeIf(String::isNotEmpty)
        val itemExtraValue = filter.itemExtraValue
        require(itemExtraValue == null || itemExtraKey != null) {
            "Activation itemExtraValue requires itemExtraKey"
        }
        if (itemExtraKey != null) {
            require(isValidItemExtraKey(itemExtraKey)) {
                "Activation itemExtraKey must be 1–128 characters and match [a-z][a-z0-9_]*"
            }
        }
        require(itemExtraValue == null || itemExtraValue.length <= 512) {
            "Activation itemExtraValue must be at most 512 characters"
        }
        require(
            filter.eventType != null || elementType != null || elementId != null || pagePath != null ||
                pagePathPrefixes.isNotEmpty() || itemExtraKey != null
        ) {
            "Activation filter must set at least one event, element, page, or item-extra predicate"
        }
        return filter.copy(
            elementType = elementType,
            elementId = elementId,
            pagePath = pagePath,
            pagePathPrefixes = pagePathPrefixes,
            itemExtraKey = itemExtraKey,
        )
    }

    private fun normalizePagePathPrefixes(prefixes: List<String>): List<String> {
        val normalized = prefixes.map(String::trim).filter(String::isNotEmpty).distinct()
        require(normalized.size <= 32) { "At most 32 page path prefixes may be configured" }
        require(normalized.all { it.length <= 512 }) { "Page path prefixes must be at most 512 characters" }
        return normalized
    }

    /**
     * Verifies that an experiment is in a mutable state (DRAFT or PAUSED) for goal mutations.
     */
    private suspend fun requireMutableExperiment(experimentId: UUID) {
        val experiment = experimentRepository.getById(experimentId)
            ?: error("Experiment not found: $experimentId")
        require(experiment.status == ExperimentStatus.DRAFT || experiment.status == ExperimentStatus.PAUSED) {
            "Cannot modify goals on an experiment with status ${experiment.status}"
        }
    }

    companion object {
        private val ASSIGNMENT_CACHE_TTL = 30.minutes

        /**
         * Deterministically buckets an identifier into one of a rollout's variation
         * weights. The hash inputs are (flagKey, salt, ruleId, identifier) — the same
         * inputs used by the flag evaluation engine, so a user always lands in the same
         * variation regardless of whether the path is "evaluate the rule directly" or
         * "evaluate the rule through an attached experiment."
         *
         * Variation weights are sorted alphabetically by [variationKey] to give a
         * stable iteration order, then assigned contiguous bucket ranges based on
         * weight. This is what enables gradual-rollout stability: increasing one
         * variation's weight absorbs users from the adjacent range rather than
         * reshuffling everyone.
         */
        fun bucketRollout(
            flagKey: String,
            salt: String,
            ruleId: String,
            rollout: Rollout,
            identifier: String
        ): String {
            val hash = stableHash("$flagKey:$salt:$ruleId:$identifier")
            val sorted = rollout.variationWeights.sortedBy { it.variationKey }
            // Sum into Long, not Int, to defend against pathological weight
            // configurations whose Int sum could overflow before the modulo.
            // Bucket math is also done in Long so the upper bits of `hash`
            // survive the reduction.
            val totalWeight: Long = sorted.sumOf { it.weight.toLong() }
            require(totalWeight > 0L) { "Total rollout weight must be positive" }
            val bucket: Long = hash % totalWeight
            var cumulative = 0L
            for (vw in sorted) {
                cumulative += vw.weight.toLong()
                if (bucket < cumulative) return vw.variationKey
            }
            return sorted.last().variationKey
        }

        /**
         * Produces a stable, non-negative 63-bit hash value from a string using
         * the FNV-1a 64-bit algorithm.
         *
         * FNV-1a is used here (instead of SHA-256) for three reasons:
         *
         *  1. **Language-portable**: the algorithm is two constants and a
         *     loop, so a JavaScript / TypeScript port produces byte-identical
         *     hashes for the same UTF-8 input. The TS-side parity fixture in
         *     `web/analytics/test/feature_flags_bucketing.spec.ts` and the
         *     Kotlin-side `TargetingBucketingFixtureTest` both load the same
         *     `targeting_bucketing_fixture.json` and assert the same buckets,
         *     so any future drift between the two implementations is caught
         *     by tests rather than silently corrupting experiments.
         *  2. **Allocation-free per call**: no `MessageDigest.getInstance`
         *     lookup, no temporary digest object, no `Provider` table walk.
         *     Bucketing is on the flag-evaluation hot path so per-call
         *     allocation matters.
         *  3. **Hash quality is sufficient**: bucket assignment only needs
         *     uniformity over `totalWeight` buckets (typically <1000), not
         *     cryptographic resistance. FNV-1a is well-tested for this use.
         *
         * Returned values are masked with `Long.MAX_VALUE` to drop the sign
         * bit so the subsequent `% totalWeight` is well-defined.
         */
        fun stableHash(input: String): Long {
            val bytes = input.toByteArray(Charsets.UTF_8)
            // FNV offset basis (14695981039346656037 unsigned) and prime
            // (1099511628211) — see http://isthe.com/chongo/tech/comp/fnv/.
            var hash = -3750763034362895579L
            for (b in bytes) {
                hash = hash xor (b.toLong() and 0xFFL)
                hash *= 1099511628211L
            }
            return hash and Long.MAX_VALUE
        }
    }
}
