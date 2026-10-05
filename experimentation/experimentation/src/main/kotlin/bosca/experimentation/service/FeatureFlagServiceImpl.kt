package bosca.experimentation.service

import bosca.analytics.model.Device
import bosca.analytics.model.Element
import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.server.ServerAnalyticsClient
import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.withRequestCache
import bosca.db.afterCommit
import bosca.db.withConnectionManager
import bosca.experimentation.model.Condition
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.EXPERIMENT_UPDATED_CHANNEL
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentUpdated
import bosca.experimentation.model.FLAG_UPDATED_CHANNEL
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.FlagEvaluation
import bosca.experimentation.events.FeatureFlagCreated
import bosca.experimentation.events.FeatureFlagDeleted
import bosca.experimentation.events.FeatureFlagStatusChanged
import bosca.experimentation.events.FeatureFlagUpdated
import bosca.experimentation.events.dispatch
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagUpdateAction
import bosca.experimentation.model.FlagUpdated
import bosca.experimentation.model.FlagValueValidator
import bosca.experimentation.model.Variation
import bosca.experimentation.repository.AssignmentRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.repository.FlagAssignmentRepository
import bosca.observability.ErrorCapture
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Implementation of [FeatureFlagService] for the variation-based flag model.
 *
 * Evaluation flow:
 *   1. Kill switch active → default variation
 *   2. Flag not active → default variation
 *   3. Walk targeting rules top-to-bottom; for each rule whose conditions all match,
 *      bucket the user across the rule's rollout to pick a variation. If the user
 *      lands in a variation, return it.
 *   4. No rule matched → return default variation
 *
 * If a running experiment is attached to a rule (or to the default-variation path),
 * the same bucketing produces the variation key — the experiment's only job is to
 * record the exposure for analysis. There is no separate "experiment evaluation"
 * code path that overrides the rule rollout.
 */
@ServiceImplementation
class FeatureFlagServiceImpl(
    private val flagRepository: FeatureFlagRepository,
    private val experimentRepository: ExperimentRepository,
    private val assignmentRepository: AssignmentRepository,
    private val flagAssignmentRepository: FlagAssignmentRepository,
    private val analyticsClient: ServerAnalyticsClient,
    private val segmentService: SegmentService,
    private val profileService: ProfileService,
    private val pubSubService: PubSubService,
    private val experimentService: ExperimentService,
    private val json: Json,
    private val errorCapture: ErrorCapture,
) : FeatureFlagService {

    // -----------------------------------------------------------------
    // Caches
    //
    // Feature flag evaluation is the hot path operators care about for
    // app startup latency. The three caches below collapse the steady-
    // state evaluation cost from "DB query + JSON parses + experiment
    // lookup per rule" down to "request-local HashMap hits".
    //
    // All three are invalidated by pub/sub events:
    //   - flagCache + activeFlagKeysCache via FLAG_UPDATED_CHANNEL,
    //     which `publishFlagUpdate` already fires on add/edit/delete/
    //     setStatus/regenerateSalt below.
    //   - runningExperimentCache via EXPERIMENT_UPDATED_CHANNEL, which
    //     `ExperimentServiceImpl.publishExperimentUpdate` fires on
    //     create/edit/delete/setStatus.
    //
    // Negative results (flag not found, no running experiment) are
    // cached too — see `RequestCache.get`'s handling of `fromCache.exists`,
    // which makes "no value" a first-class cached state. Without negative
    // caching, the absence-of-experiment hot path (the common case for
    // most rules) would still hit the DB on every call.
    // -----------------------------------------------------------------

    /**
     * Single FeatureFlag rows keyed by `key`. Resolver hits
     * `flagRepository.getByKey`. Returns null when the flag does not
     * exist; the null is cached so subsequent lookups for unknown
     * keys also avoid the DB.
     */
    private val flagCache = ServiceCache<String, FeatureFlag>(
        cacheName = "experimentation:flag:by-key",
        serializer = StringKeySerializer,
        // Safety-net TTL so a missed pub/sub invalidation event cannot
        // leave per-flag entries stale indefinitely. Matches the
        // ACTIVE_FLAG_KEYS_TTL rationale.
        expiration = PER_FLAG_CACHE_TTL,
    ) { key ->
        flagRepository.getByKey(key)
    }

    /**
     * Single FeatureFlag rows keyed by UUID. Used by `getById` so
     * GraphQL field resolvers that walk from an experiment back to
     * its parent flag hit the cache instead of issuing a PG
     * round-trip per row.
     *
     * Invalidated alongside [flagCache] on every flag mutation via
     * the same `FLAG_UPDATED_CHANNEL` pub/sub event.
     */
    private val flagByIdCache = ServiceCache<UUID, FeatureFlag>(
        cacheName = "experimentation:flag:by-id",
        serializer = UUIDKeySerializer,
        expiration = PER_FLAG_CACHE_TTL,
    ) { id ->
        flagRepository.getById(id)
    }

    /**
     * Sorted list of every active (status = ENABLED) flag's key, under
     * a single fixed cache entry. The resolver fetches the bulk
     * `getAllActive` once and ALSO populates [flagCache] entries for
     * every flag it just loaded — so a cold-start `evaluateAll` issues
     * exactly one DB round-trip instead of `1 + N` (the list query
     * plus a per-flag query when the loop calls `flagCache.get(key)`).
     *
     * Invalidated on every flag add/edit/delete/status change because
     * any of those can change the active set membership; the cost of
     * a stray full reload is a single SELECT, well worth the simpler
     * invariant.
     */
    private val activeFlagKeysCache = ServiceCache<String, List<String>>(
        cacheName = "experimentation:flags:active-keys",
        serializer = StringKeySerializer,
        // Soft TTL: forces every node to rebuild the active set at least
        // once a minute even if a FLAG_UPDATED pub/sub message is dropped
        // (NATS best-effort under load) or a node missed an event during
        // a transient subscriber failure. Without this the single-entry
        // cache could be stale forever after a missed invalidation —
        // every other invalidation path is correct, but the failure mode
        // for "missed event" is unbounded staleness.
        expiration = ACTIVE_FLAG_KEYS_TTL,
    ) { _ ->
        val flags = flagRepository.getAllActive()
        // Side-effect warm of the per-flag cache. Without this, the
        // bulk SELECT we just did would be wasted: the loop in
        // evaluateAll below would re-query every flag individually
        // because flagCache.get(key) would still see a miss.
        flags.forEach { flag -> flagCache.put(flag.key, flag) }
        flags.map { it.key }
    }

    /**
     * Running experiments keyed by `"flagId:ruleId"` (ruleId may be the
     * empty string for the default-variation path). Negative entries
     * cached so the common "no experiment attached to this rule" path
     * stays out of the DB.
     */
    private val runningExperimentCache = ServiceCache<String, Experiment>(
        cacheName = "experimentation:experiment:running-by-rule",
        serializer = StringKeySerializer,
        expiration = PER_FLAG_CACHE_TTL,
    ) { key ->
        // Keys are created only by findRunningExperiment and always contain
        // the separator, including for the empty default-rule id.
        val flagIdString = key.substringBefore(':')
        val ruleIdRaw = key.substringAfter(':')
        val flagId = UUID.parse(flagIdString)
        val ruleId = ruleIdRaw.ifEmpty { null }
        experimentRepository.getRunningByFlagAndRule(flagId, ruleId)
    }

    /**
     * Stateless evaluator that resolves a flag + evaluation context into a
     * variation. Extracted so the core evaluation logic — rule matching,
     * condition evaluation, bucketing — is independently testable without
     * the service's cache/pub-sub/repository wiring.
     */
    private val evaluator = FlagEvaluator(
        segmentService = segmentService,
        json = json,
        flagLookup = { key -> flagCache.get(key) },
        findRunningExperiment = { flagId, ruleId -> findRunningExperiment(flagId, ruleId) },
        isExcludedFromLayer = { layerId, experimentId, principalId, installationId ->
            userIsExcludedFromLayer(layerId, experimentId, principalId, installationId)
        },
        recordAssignment = { experimentId, flagKey, principalId, installationId ->
            recordAssignment(experimentId, flagKey, principalId, installationId)
        },
        recordFlagAssignment = { flag, ruleId, principalId, installationId, device, variationKey ->
            recordFlagAssignment(flag, ruleId, principalId, installationId, device, variationKey)
        },
    )

    /**
     * Long-lived coroutine scope hosting the pub/sub invalidation
     * subscribers. SupervisorJob so a failure in one subscriber does
     * not tear down the other; per-subscriber loops retry on failure.
     *
     * In production this is the default `Dispatchers.Default`-backed
     * scope. Tests inject a scope tied to the test runner so the
     * subscribers respect virtual time and get cancelled at the end of
     * the test rather than leaking onto a global dispatcher and
     * accumulating across the suite.
     */
    private val invalidationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineName("flag-invalidation-subscriber"))

    /**
     * Large, bounded channel feeding the best-effort [flagAssignmentWriterJob].
     * Assignment persistence and analytics stay off the latency-sensitive
     * evaluation path. Sustained traffic that exceeds this buffer drops the
     * oldest queued writes and logs the cumulative drop count.
     *
     * The repository reports whether the variation actually changed, so
     * repeated evaluations do not emit duplicate history events.
     */
    private val droppedFlagAssignments = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastDropLogTimeMs: Long = 0L

    private val flagAssignmentChannel = kotlinx.coroutines.channels.Channel<FlagAssignmentWriteRequest>(
        capacity = FLAG_ASSIGNMENT_CHANNEL_CAPACITY,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { recordDroppedFlagAssignment() },
    )

    private val flagAssignmentWriterJob = invalidationScope.launch {
        for (request in flagAssignmentChannel) {
            try {
                val changed = withConnectionManager {
                    flagAssignmentRepository.record(
                        flagId = request.flagId,
                        installationId = request.installationId,
                        principalId = request.principalId,
                        variationKey = request.variationKey,
                        assignedAt = request.assignedAt,
                        flagModified = request.flagModified,
                    )
                }
                if (changed == true) {
                    emitFlagAssignmentEvent(request)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(
                    "Failed to record assignment for flag {} variation {}",
                    request.flagId, request.variationKey, e,
                )
            }
        }
    }

    init {
        subscribeFlagInvalidations()
        subscribeExperimentInvalidations()
    }

    /**
     * Drains any buffered assignment writes, then cancels the
     * invalidation subscribers and background jobs. The channel
     * close signals the [flagAssignmentWriterJob] to finish processing
     * remaining items; [join] awaits its completion so no writes
     * are silently lost on a clean server stop.
     *
     * Safe to call multiple times — closing an already-closed
     * channel and cancelling an already-cancelled scope are both
     * no-ops.
     */
    override suspend fun shutdown() {
        flagAssignmentChannel.close()
        flagAssignmentWriterJob.join()
        invalidationScope.cancel()
    }

    /**
     * Subscribes to [FLAG_UPDATED_CHANNEL] and dispatches each event
     * to [handleFlagUpdated]. The retry loop mirrors the pattern in
     * `PipelineEventDispatcherImpl` — pub/sub failures must not silently
     * leave us with stale caches forever. The handler is its own
     * function so tests can drive cache invalidation directly without
     * having to set up cross-thread pub/sub plumbing.
     */
    private fun subscribeFlagInvalidations() {
        invalidationScope.launch {
            var backoffMs = INITIAL_RETRY_DELAY_MS
            while (true) {
                try {
                    pubSubService.subscribe(FLAG_UPDATED_CHANNEL, FlagUpdated.serializer()).collect { msg ->
                        backoffMs = INITIAL_RETRY_DELAY_MS // reset on successful message
                        handleFlagUpdated(msg.message)
                    }
                    // Flow completed normally (empty or finite) — back off
                    // before resubscribing so a stub/broken publisher
                    // doesn't spin the CPU.
                    delay(backoffMs.milliseconds)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    log.error("PubSub subscription for flag invalidation failed; retrying in {}ms", backoffMs, e)
                    delay((backoffMs + (0..backoffMs / 4).random()).milliseconds) // jitter
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * Subscribes to [EXPERIMENT_UPDATED_CHANNEL] and dispatches each
     * event to [handleExperimentUpdated]. Same retry semantics and
     * same rationale for the extracted handler as
     * [subscribeFlagInvalidations].
     */
    private fun subscribeExperimentInvalidations() {
        invalidationScope.launch {
            var backoffMs = INITIAL_RETRY_DELAY_MS
            while (true) {
                try {
                    pubSubService.subscribe(EXPERIMENT_UPDATED_CHANNEL, ExperimentUpdated.serializer()).collect { msg ->
                        backoffMs = INITIAL_RETRY_DELAY_MS
                        handleExperimentUpdated(msg.message)
                    }
                    delay(backoffMs.milliseconds)
                } catch (e: Exception) {
                    log.error("PubSub subscription for experiment invalidation failed; retrying in {}ms", backoffMs, e)
                    delay((backoffMs + (0..backoffMs / 4).random()).milliseconds)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * Per-event invalidation handler for [FLAG_UPDATED_CHANNEL].
     * Drops the per-flag cache entry AND the active-keys list (status
     * transitions change set membership). Wrapped in [withRequestCache]
     * because `ServiceCache.remove` calls `requestCache()`, which
     * requires a RequestCache in the coroutine context: pub/sub
     * subscribers run on `Dispatchers.Default` outside of any HTTP
     * request, so without this wrapper the remove would throw and the
     * cache would never actually invalidate. The ephemeral RequestCache
     * flushes through to the underlying remote cache as soon as the
     * block completes (no DB connection → immediate flush fallback in
     * `flushAfterCommit`).
     *
     * Public so tests can drive invalidation deterministically without
     * needing to wire up real pub/sub flows on a background dispatcher.
     */
    internal suspend fun handleFlagUpdated(event: FlagUpdated) {
        withRequestCache {
            flagCache.remove(event.flagKey)
            flagByIdCache.remove(event.flagId)
            // Status changes alter the active-set membership; just
            // drop the (single) list entry rather than try to
            // surgically patch it.
            activeFlagKeysCache.remove(ACTIVE_FLAG_KEYS_KEY)
        }
    }

    /**
     * Per-event invalidation handler for [EXPERIMENT_UPDATED_CHANNEL].
     * Drops only the affected flag's running-experiment entries via
     * prefix removal on `"flagId:"`. Per-flag prefix removal keeps the
     * blast radius tight — changing one experiment doesn't invalidate
     * cached lookups for unrelated flags. Same `withRequestCache`
     * rationale as [handleFlagUpdated].
     */
    internal suspend fun handleExperimentUpdated(event: ExperimentUpdated) {
        withRequestCache {
            runningExperimentCache.remove("${event.flagId}:", keyPrefix = true)
        }
    }

    override suspend fun getAll(offset: Long, limit: Int): List<FeatureFlag> {
        return flagRepository.getAll(offset, limit)
    }

    override suspend fun getById(id: UUID): FeatureFlag? {
        return flagByIdCache.get(id)
    }

    override suspend fun getByKey(key: String): FeatureFlag? {
        return flagCache.get(key)
    }

    override suspend fun add(input: FeatureFlagInput): FeatureFlag {
        val variations = parseVariationsOrThrow(input.variations)
        FlagValueValidator.validateVariations(input.type, variations)
        require(variations.any { it.key == input.defaultVariationKey }) {
            "Default variation key '${input.defaultVariationKey}' must reference an existing variation"
        }
        validateTargetingRules(input.targetingRules, variations)
        val flag = FeatureFlag(
            key = input.key,
            name = input.name,
            description = input.description ?: "",
            type = input.type,
            status = input.status ?: FlagStatus.DRAFT,
            variations = input.variations,
            defaultVariationKey = input.defaultVariationKey,
            targetingRules = input.targetingRules
        )
        val saved = flagRepository.add(flag)
        // Publish the same FLAG_UPDATED event the other mutators fire so
        // every other instance drops its flagCache and activeFlagKeysCache
        // entries and rebuilds them on next read. Without this, on a
        // multi-instance deployment the new flag would never appear in
        // any node *other* than the one that handled the create until an
        // unrelated mutation triggered a refresh.
        publishFlagUpdate(saved.key, saved.id, FlagUpdateAction.UPDATED)
        FeatureFlagCreated(saved.id, saved.key).dispatch()
        return saved
    }

    override suspend fun edit(id: UUID, input: FeatureFlagInput): FeatureFlag {
        val variations = parseVariationsOrThrow(input.variations)
        FlagValueValidator.validateVariations(input.type, variations)
        require(variations.any { it.key == input.defaultVariationKey }) {
            "Default variation key '${input.defaultVariationKey}' must reference an existing variation"
        }
        validateTargetingRules(input.targetingRules, variations)
        val existing = flagRepository.getById(id) ?: error("Feature flag not found: $id")
        require(input.key == existing.key) {
            "Cannot change the key of an existing feature flag. " +
                    "Current key: '${existing.key}', requested: '${input.key}'"
        }
        val updated = existing.copy(
            key = input.key,
            name = input.name,
            description = input.description ?: existing.description,
            type = input.type,
            status = input.status ?: existing.status,
            variations = input.variations,
            defaultVariationKey = input.defaultVariationKey,
            targetingRules = input.targetingRules
        )
        val saved = flagRepository.update(updated)
        // An assignment to a deleted variation is no longer valid current
        // state. Remove those rows immediately; their transition history
        // remains in analytics.
        runCatching {
            // Unit-Separator-delimited because the @Query codegen rejects
            // array parameters; the SQL splits it back via string_to_array.
            // U+001F (instead of comma) so a stray comma in a variation key
            // can never silently split one key into two and prune all the
            // assignments for the flag.
            val validKeys = variations.joinToString("\u001f") { it.key }
            flagAssignmentRepository.pruneOrphanedVariations(saved.id, validKeys)
        }.onFailure { e ->
            log.warn("Failed to prune orphaned assignments for flag {}", saved.id, e)
        }
        publishFlagUpdate(saved.key, saved.id, FlagUpdateAction.UPDATED)
        FeatureFlagUpdated(saved.id, saved.key).dispatch()
        return saved
    }

    override suspend fun delete(id: UUID) {
        val flag = flagRepository.deleteById(id) ?: return
        publishFlagUpdate(flag.key, flag.id, FlagUpdateAction.DELETED)
        FeatureFlagDeleted(flag.id, flag.key).dispatch()
    }

    override suspend fun setStatus(id: UUID, status: FlagStatus): FeatureFlag {
        val updated = flagRepository.updateStatus(id, status)
            ?: error("Feature flag not found: $id")
        publishFlagUpdate(updated.key, updated.id, FlagUpdateAction.UPDATED)
        FeatureFlagStatusChanged(updated.id, updated.key, updated.status).dispatch()
        return updated
    }

    override suspend fun regenerateSalt(id: UUID): FeatureFlag {
        val updated = flagRepository.regenerateSalt(id)
            ?: error("Feature flag not found: $id")
        // Existing assignments remain current until those subjects evaluate
        // against the new salt. A resulting variation change updates the
        // assignment time and emits an analytics transition event.
        publishFlagUpdate(updated.key, updated.id, FlagUpdateAction.UPDATED)
        FeatureFlagUpdated(updated.id, updated.key).dispatch()
        return updated
    }

    override suspend fun getVariationAssignments(
        flagId: UUID,
    ): List<bosca.experimentation.model.VariationAssignmentCount> {
        return flagAssignmentRepository.getDistribution(flagId)
    }

    override suspend fun evaluate(
        flagKey: String,
        principalId: UUID?,
        installationId: String,
        device: bosca.analytics.model.Device?
    ): FlagEvaluation {
        require(installationId.isNotBlank()) { "Installation ID is required for feature flag evaluation" }
        val flag = try {
            flagCache.get(flagKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportEvaluationFailure(e, flagKey, "load")
            return unavailableEvaluation(flagKey)
        }
        if (flag == null) {
            log.warn("Evaluated unknown flag key '{}'", flagKey)
            return unavailableEvaluation(flagKey)
        }
        val ctx = EvaluationContext(principalId, installationId, device, profileService)
        return try {
            evaluator.evaluateFlag(flag, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportEvaluationFailure(e, flagKey, "evaluate")
            degradedDefaultEvaluation(flag)
        }
    }

    override suspend fun evaluateAll(
        principalId: UUID?,
        installationId: String,
        device: bosca.analytics.model.Device?
    ): List<FlagEvaluation> {
        require(installationId.isNotBlank()) { "Installation ID is required for feature flag evaluation" }
        val keys = try {
            activeFlagKeysCache.get(ACTIVE_FLAG_KEYS_KEY) ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportEvaluationFailure(e, null, "load-active")
            return emptyList()
        }
        // Resolve segments AND profile attributes from a single
        // getByPrincipal call so the EvaluationContext doesn't re-fetch
        // the same profiles when a flag has a ProfileAttribute condition.
        var preloadedAttributes: List<ProfileAttribute>? = null
        var preloadDegraded = false
        val memberSegmentIds: Set<UUID> = if (principalId != null) {
            try {
                val profiles = profileService.getByPrincipal(principalId)
                preloadedAttributes = profiles.flatMap { profile ->
                    try {
                        profileService.getAttributes(profile.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Failed to preload attributes for profile {} — attribute conditions will use defaults for this evaluation", profile.id, e)
                        preloadDegraded = true
                        emptyList()
                    }
                }
                profiles.flatMap { profile ->
                    segmentService.getSegmentsByProfileId(profile.id).map { it.id }
                }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to resolve segments/profile for principal {} — all segment and attribute targeting will fall back to defaults", principalId, e)
                preloadDegraded = true
                emptySet()
            }
        } else {
            preloadedAttributes = emptyList()
            emptySet()
        }
        val ctx = EvaluationContext(
            principalId, installationId, device, profileService,
            memberSegmentIds = memberSegmentIds,
            profileAttributes = preloadedAttributes,
        )
        if (preloadDegraded) ctx.markDegraded()
        return keys.map { key ->
            val flag = try {
                flagCache.get(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportEvaluationFailure(e, key, "load")
                return@map unavailableEvaluation(key)
            }
            if (flag == null) {
                log.warn("Active feature flag '{}' disappeared during bulk evaluation", key)
                return@map unavailableEvaluation(key)
            }
            try {
                evaluator.evaluateFlag(flag, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportEvaluationFailure(e, key, "evaluate")
                degradedDefaultEvaluation(flag)
            }
        }
    }

    /**
     * Produces the fail-closed value used when a flag cannot be loaded at all.
     * A blank variation key makes typed consumers use their authored fallback.
     */
    private fun unavailableEvaluation(flagKey: String): FlagEvaluation = FlagEvaluation(
        flagKey = flagKey,
        variationKey = "",
        value = JsonNull,
        degraded = true,
    )

    /**
     * Preserves a known flag's declared default when targeting or experiment
     * evaluation fails. Corrupt variation JSON falls back one step further to
     * an unavailable evaluation, keeping feature failures off the request path.
     */
    private fun degradedDefaultEvaluation(flag: FeatureFlag): FlagEvaluation =
        try {
            evaluator.defaultEvaluation(flag, degraded = true)
        } catch (_: Exception) {
            unavailableEvaluation(flag.key)
        }

    private suspend fun reportEvaluationFailure(
        error: Exception,
        flagKey: String?,
        operation: String,
    ) {
        if (flagKey == null) {
            log.error("Failed to load active feature flags — returning an empty degraded snapshot", error)
        } else {
            log.error("Failed to {} feature flag '{}' — returning its degraded fallback", operation, flagKey, error)
        }
        errorCapture.capture(
            error,
            null,
            mapOf("operation" to operation, "flagKey" to flagKey),
        )
    }

    /**
     * Enqueues current flag assignment persistence without adding a database
     * round-trip to evaluation latency. The writer records [assignedAt] only
     * when the variation is first assigned or changes; it then emits exactly
     * one analytics assignment event for that transition.
     */
    private fun recordFlagAssignment(
        flag: FeatureFlag,
        ruleId: String?,
        principalId: UUID?,
        installationId: String,
        device: Device?,
        variationKey: String,
    ) {
        require(installationId.isNotBlank()) { "Installation ID is required to record a flag assignment" }
        require(variationKey.isNotBlank()) { "Variation key is required to record a flag assignment" }
        val result = flagAssignmentChannel.trySend(
            FlagAssignmentWriteRequest(
                flagId = flag.id,
                flagKey = flag.key,
                flagModified = flag.modified,
                targetingRuleId = ruleId,
                principalId = principalId,
                installationId = installationId,
                device = device,
                variationKey = variationKey,
                assignedAt = bosca.serialization.OffsetDateTime.now(),
            )
        )
        if (result.isFailure) recordDroppedFlagAssignment()
    }

    private fun recordDroppedFlagAssignment() {
        val count = droppedFlagAssignments.incrementAndGet()
        val now = System.currentTimeMillis()
        if (count == 1L || now - lastDropLogTimeMs >= FLAG_ASSIGNMENT_DROP_LOG_INTERVAL_MS) {
            lastDropLogTimeMs = now
            log.warn(
                "Flag assignment channel dropped a write (capacity {}, {} total dropped); " +
                    "traffic exceeded persistence throughput or the writer was closed",
                FLAG_ASSIGNMENT_CHANNEL_CAPACITY, count,
            )
        }
    }

    private suspend fun emitFlagAssignmentEvent(request: FlagAssignmentWriteRequest) {
        val extras = buildJsonObject {
            put("flagKey", request.flagKey)
            put("variationKey", request.variationKey)
            put("flagModified", request.flagModified.toString())
            request.targetingRuleId?.let { put("targetingRuleId", it) }
        }
        analyticsClient.captureForSubject(
            event = Event(
                created = request.assignedAt.toInstant().toEpochMilli(),
                type = EventType.Assignment,
                element = Element(
                    id = request.flagId.toString(),
                    type = "feature_flag",
                    extras = extras,
                ),
            ),
            userId = request.principalId?.toString(),
            installationId = request.installationId,
            device = request.device,
        )
    }

    /**
     * Persists an experiment assignment for the current user/device.
     *
     * Delegates to [ExperimentService.assignVariation], which handles the
     * full lifecycle: idempotent insert (so repeated `evaluate` calls for
     * the same user don't create duplicate rows), exclusion-layer checks,
     * and the actual bucket computation.
     *
     * Returns the persisted assignment (either freshly inserted or already
     * present), and null when the write failed. The evaluator uses the
     * persisted variation as its source of truth so an anonymous installation
     * keeps the same experience after it logs in.
     *
     * The caller MUST gate `experimentId` in the returned
     * `FlagEvaluation` on this method's return value. Returning the user
     * a variation tagged with an experiment id while the assignment row
     * does not exist silently under-counts conversions: the user
     * converts later, the aggregation job looks up their assignment,
     * finds nothing, and the conversion is dropped. The user-facing
     * evaluate call still succeeds (this method does not throw) so the
     * request itself is unaffected; the only difference is that the
     * response no longer claims an experiment that we cannot honor.
     */
    private suspend fun recordAssignment(
        experimentId: UUID,
        flagKey: String,
        principalId: UUID?,
        installationId: String,
    ): Assignment? {
        return try {
            experimentService.assignVariation(experimentId, principalId, installationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "Failed to record assignment for experiment {} on flag {} (principal={}, install={}) — " +
                        "dropping experimentId from evaluation response so the conversion path stays consistent",
                experimentId, flagKey, principalId, installationId, e,
            )
            null
        }
    }

    /**
     * Returns the running experiment attached to a `(flag, rule)` pair, if
     * any, including its `exclusionLayerId` so the evaluator can decide
     * whether to skip the rule for users already in another experiment in
     * the same layer. Returns null when no experiment is attached.
     *
     * Cached behind [runningExperimentCache]; both positive and negative
     * results are stored, so the very common "no experiment attached
     * to this rule" path stays out of the database. Invalidated by the
     * `EXPERIMENT_UPDATED_CHANNEL` subscriber on every experiment
     * mutation that touches this flag.
     */
    private suspend fun findRunningExperiment(flagId: UUID, ruleId: String?): Experiment? {
        return runningExperimentCache.get("$flagId:${ruleId ?: ""}")
    }

    /**
     * Returns true when the given user already has an assignment to a
     * different experiment in the supplied exclusion layer — i.e. the
     * exclusion check fires and the rule should not match for this user.
     *
     * Every evaluation has an installation id. When a principal is also
     * present, both identity paths are checked so logging in cannot bypass
     * an exclusion established by an earlier anonymous assignment.
     */
    private suspend fun userIsExcludedFromLayer(
        layerId: UUID,
        experimentId: UUID,
        principalId: UUID?,
        installationId: String,
    ): Boolean {
        // Union both identifier paths rather than short-circuiting on
        // principalId. A user who logged in after first being identified
        // by installationId can have a stale assignment row keyed by
        // installation id from before they logged in; checking only the
        // principal would miss it and let them be enrolled in a second,
        // overlapping experiment in the same exclusion layer.
        if (principalId != null) {
            val hit = assignmentRepository.existsForOtherExperimentInLayerByPrincipal(
                layerId, experimentId, principalId,
            )
            if (hit) return true
        }
        val hit = assignmentRepository.existsForOtherExperimentInLayerByInstallation(
            layerId, experimentId, installationId,
        )
        if (hit) return true
        return false
    }

    /**
     * Parses the flag's variations JSON into typed [Variation] objects. Used
     * by the write-path validators; the read-path delegates to [evaluator].
     */
    private fun parseVariationsOrThrow(variationsJson: JsonElement): List<Variation> =
        evaluator.parseVariations(variationsJson)

    /**
     * Write-path validation: every targeting rule must reference variations that
     * exist in the flag's palette, and rule ids must be unique. Called from add/edit.
     */
    private suspend fun validateTargetingRules(rulesJson: JsonElement?, variations: List<Variation>) {
        if (rulesJson == null) return
        val rules = evaluator.parseTargetingRules(rulesJson)
        val variationKeys = variations.map { it.key }.toSet()
        val seenIds = mutableSetOf<String>()
        rules.forEachIndexed { index, rule ->
            require(rule.id.isNotBlank()) { "Rule ${index + 1} is missing a stable id" }
            require(seenIds.add(rule.id)) { "Duplicate rule id: ${rule.id}" }
            for (vw in rule.rollout.variationWeights) {
                require(vw.variationKey in variationKeys) {
                    "Rule ${index + 1} rollout references unknown variation '${vw.variationKey}'"
                }
            }
            for (condition in rule.conditions) {
                if (condition is Condition.FlagDependency) {
                    validateFlagDependencyNoCycle(condition.flagKey, mutableSetOf())
                }
            }
        }
    }

    /**
     * Walks the FlagDependency prerequisite chain at save time to detect
     * cycles. A cycle that reaches [startKey] again means evaluation would
     * loop infinitely; rejecting it here surfaces the error in the admin
     * UI mutation instead of silently falling back at evaluation time.
     */
    private suspend fun validateFlagDependencyNoCycle(flagKey: String, visited: MutableSet<String>) {
        require(visited.add(flagKey)) {
            "FlagDependency cycle detected: ${visited.joinToString(" → ")} → $flagKey"
        }
        val dep = flagCache.get(flagKey) ?: return
        val depRules = evaluator.parseTargetingRules(dep.targetingRules)
        for (rule in depRules) {
            for (condition in rule.conditions) {
                if (condition is Condition.FlagDependency) {
                    validateFlagDependencyNoCycle(condition.flagKey, visited)
                }
            }
        }
        visited.remove(flagKey)
    }

    private suspend fun publishFlagUpdate(flagKey: String, flagId: UUID, action: FlagUpdateAction) {
        afterCommit {
            try {
                pubSubService.publish(
                    FLAG_UPDATED_CHANNEL,
                    FlagUpdated.serializer(),
                    FlagUpdated(flagKey = flagKey, flagId = flagId, action = action)
                )
            } catch (e: Exception) {
                log.error("Failed to publish flag update for '{}'", flagKey, e)
                errorCapture.capture(e, null, mapOf("flagKey" to flagKey, "flagId" to flagId.toString(), "action" to action.name))
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FeatureFlagServiceImpl::class.java)

        /**
         * Single fixed key under which `activeFlagKeysCache` stores the
         * sorted list of every active (ENABLED) flag's key. Constant
         * because the cache only ever holds one entry — the entire
         * active set — and a stable string key keeps the StringKeySerializer
         * happy without needing a custom Unit serializer.
         */
        private const val ACTIVE_FLAG_KEYS_KEY = "all"

        /**
         * Soft TTL on the single-entry [activeFlagKeysCache] so a missed
         * `FLAG_UPDATED` invalidation event cannot leave a node serving
         * a stale active-flag set forever.
         */
        private val ACTIVE_FLAG_KEYS_TTL: Duration = 60.seconds

        /**
         * Safety-net TTL for per-flag and per-experiment caches so a missed
         * pub/sub invalidation event cannot leave entries stale indefinitely.
         * Aligned with [ACTIVE_FLAG_KEYS_TTL] — after this window, a cache
         * miss triggers a DB round-trip to rebuild the entry.
         */
        private val PER_FLAG_CACHE_TTL: Duration = 60.seconds

        /** Allows large assignment bursts without making flag evaluation wait on persistence. */
        private const val FLAG_ASSIGNMENT_CHANNEL_CAPACITY = 100_000

        /** Initial retry delay for pub/sub subscriber reconnection. */
        private const val INITIAL_RETRY_DELAY_MS = 1_000L

        /** Maximum retry delay — caps exponential backoff at 60 seconds. */
        private const val MAX_RETRY_DELAY_MS = 60_000L

        /** Minimum interval between assignment-drop log messages. */
        private const val FLAG_ASSIGNMENT_DROP_LOG_INTERVAL_MS = 60_000L
    }

    /**
     * One pending current-assignment transition. The flag configuration
     * timestamp lets the repository reject writes queued before an edit.
     */
    private data class FlagAssignmentWriteRequest(
        val flagId: UUID,
        val flagKey: String,
        val flagModified: bosca.serialization.OffsetDateTime,
        val targetingRuleId: String?,
        val principalId: UUID?,
        val installationId: String,
        val device: Device?,
        val variationKey: String,
        val assignedAt: bosca.serialization.OffsetDateTime,
    )
}
