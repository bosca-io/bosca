package bosca.content.transition.service

import bosca.content.collection.model.ContentItem
import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.state.model.WorkflowStateType
import bosca.content.state.service.StateService
import bosca.content.transition.jobs.UpdateContentJobHistoryOnCompleteListener
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.model.JobHistory
import bosca.content.transition.model.Transition
import bosca.db.connectionOrNull
import bosca.di.provide
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration

/**
 * Specifies the phase of a workflow transition being executed.
 *
 * Each transition between workflow states can involve up to three job phases:
 * an exit job from the current state, an enter job into the target state,
 * and a default job defined by the target state itself.
 */
private enum class TransitionPhase {
    /** The primary job defined by the target workflow state. */
    DEFAULT,

    /** The job executed when entering the target workflow state. */
    ENTER,

    /** The job executed when leaving the current workflow state. */
    EXIT
}

/**
 * Orchestrates workflow state transitions for content items (metadata and collections).
 *
 * When a content item moves between workflow states (e.g., "pending" to "draft", "draft" to "published"),
 * this class coordinates the full lifecycle:
 *
 * 1. **Resolves** the content item and creates a type-safe operations adapter via [ContentItemOperations]
 * 2. **Validates** the transition exists, the caller has EXECUTE permission, and the item is ready
 * 3. **Computes scheduling** via [TransitionScheduleResolver] for advertised/published epoch-based delays
 * 4. **Sets pending state** so the system knows a transition is in progress
 * 5. **Enqueues transition phases** (EXIT → ENTER → DEFAULT) as background jobs
 * 6. **Handles failure** by rolling back the pending state if any phase fails to enqueue
 *
 * @property metadataService provides CRUD and state mutation operations for metadata items
 * @property metadataJobHistory tracks job execution history for metadata transitions
 * @property collectionService provides CRUD and state mutation operations for collections
 * @property collectionJobHistory tracks job execution history for collection transitions
 * @property transitionService resolves valid transitions between workflow states
 * @property stateService looks up workflow state definitions and their associated job configurations
 * @property metadataPermissionEvaluator enforces authorization for metadata operations
 * @property collectionPermissionEvaluator enforces authorization for collection operations
 */
class Transitioner(
    private val metadataService: MetadataService,
    private val metadataJobHistory: MetadataJobHistoryService,
    private val collectionService: CollectionService,
    private val collectionJobHistory: CollectionJobHistoryService,
    private val transitionService: TransitionService,
    private val stateService: StateService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator
) {

    /**
     * Initiates a workflow state transition for a metadata item or collection.
     *
     * Individual database operations commit independently — this method must not be called
     * inside a transaction because [setPendingState] and job enqueuing must each commit
     * separately to avoid stranding items in a pending state on partial failure.
     *
     * @param authentication the caller's security context, used for permission checks
     * @param request specifies the target content item, destination state, and transition options
     * @param item optional pre-loaded content item; if null, resolved from the request's IDs
     * @return the [ContentItem] after the transition has been initiated
     */
    suspend fun beginTransition(
        authentication: AuthenticationContext,
        request: BeginTransitionInput,
        item: ContentItem? = null
    ): ContentItem {
        if (connectionOrNull()?.inTransaction == true) {
            error("cannot call beginTransition inside a transaction")
        }

        val ops = ContentItemOperations.resolve(
            request, item,
            metadataService, collectionService,
            metadataJobHistory, collectionJobHistory,
            metadataPermissionEvaluator, collectionPermissionEvaluator
        )

        return executeTransition(authentication, request, ops)
    }

    /**
     * Cancels the most recently enqueued transition job for a content item.
     *
     * Resolves the target content item from the provided identifiers, verifies EXECUTE permission,
     * cancels all active jobs in the queue, and marks the pending state as failed.
     *
     * @param authentication the caller's security context
     * @param collectionId the collection to cancel the job for, or null if targeting metadata
     * @param languageTag optional language tag to resolve a collection language variant
     * @param metadataId the metadata item to cancel the job for, or null if targeting a collection
     * @param metadataVersion the version of the metadata item (required when [metadataId] is provided)
     */
    suspend fun cancelLatestJob(
        authentication: AuthenticationContext,
        collectionId: UUID? = null,
        languageTag: String? = null,
        metadataId: UUID? = null,
        metadataVersion: Int? = null,
    ) {
        val request = BeginTransitionInput(
            collectionId = collectionId,
            metadataId = metadataId,
            version = metadataVersion,
            languageTag = languageTag,
            stateId = "",
            status = "",
        )
        val ops = ContentItemOperations.resolve(
            request, null,
            metadataService, collectionService,
            metadataJobHistory, collectionJobHistory,
            metadataPermissionEvaluator, collectionPermissionEvaluator
        )
        ops.cancelActiveJobs(authentication)
    }

    private suspend fun executeTransition(
        authentication: AuthenticationContext,
        request: BeginTransitionInput,
        ops: ContentItemOperations,
    ): ContentItem {
        val principal = authentication.principal() ?: error("no principal")
        val content = ops.item

        ops.verifyPermission(authentication)
        validatePreConditions(content, request)

        if (content.workflowStatePendingId != null) {
            handleExistingPendingState(content, principal, ops)
        }

        val transition = resolveTransition(content.workflowStateId, request.stateId)
        val schedule = TransitionScheduleResolver.resolve(request, content)
        val effectiveRequest = schedule.request

        var pending: ContentItem? = null
        try {
            pending = ops.setPendingState(principal, effectiveRequest)

            enqueuePhase(transition, TransitionPhase.EXIT, pending, effectiveRequest, schedule.delay, principal, ops)
            enqueuePhase(transition, TransitionPhase.ENTER, pending, effectiveRequest, schedule.delay, principal, ops)

            val defaultEnqueued = enqueuePhase(
                transition, TransitionPhase.DEFAULT, pending, effectiveRequest, schedule.delay, principal, ops
            )

            if (defaultEnqueued == null && schedule.delay == null) {
                return ops.setPendingStateComplete(pending, principal, effectiveRequest)
            }
        } catch (e: Exception) {
            if (pending != null) {
                try {
                    ops.setPendingStateFailed(pending, principal, e.message ?: "Transition failed")
                } catch (rollbackError: Exception) {
                    log.error("failed to roll back pending state after transition failure", rollbackError)
                }
            }
            throw e
        }

        return pending
    }

    private fun validatePreConditions(content: ContentItem, request: BeginTransitionInput) {
        if ((request.restart == null || !request.restart!!) && content.workflowStateId == request.stateId) {
            error("content is already in state '${request.stateId}'")
        }
    }

    private suspend fun handleExistingPendingState(
        content: ContentItem,
        principal: AuthenticatedPrincipal,
        ops: ContentItemOperations,
    ) {
        when (content) {
            is Metadata -> ops.setPendingStateFailed(content, principal, "restarting a pending transition")
            is ICollection -> error("collection is already in a pending state")
        }
    }

    private suspend fun resolveTransition(currentStateId: String?, targetStateId: String): Transition {
        val fromState = currentStateId ?: "pending"
        return transitionService.get(fromState, targetStateId)
            ?: error("transition doesn't exist: $fromState -> $targetStateId")
    }

    /**
     * Enqueues a single phase of a workflow transition (EXIT, ENTER, or DEFAULT).
     *
     * Validates that the content item is ready and not blocked, resolves the job name for
     * the phase, builds a merged configuration, and enqueues the job — either immediately
     * or with a delay.
     *
     * @return the persisted job history record, or null if no job was enqueued
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun enqueuePhase(
        transition: Transition,
        phase: TransitionPhase,
        item: ContentItem,
        request: BeginTransitionInput,
        delayUntil: OffsetDateTime?,
        principal: AuthenticatedPrincipal,
        ops: ContentItemOperations,
    ): JobHistory? {
        val state = stateService.get(item.workflowStateId)
        if (state != null && state.type == WorkflowStateType.PENDING && !request.allowProcessing && item.ready == null) {
            error("manual transition to processing isn't allowed, please mark as ready instead and wait for the item to be transitioned to draft")
        }
        if (item.ready == null && state != null && state.type != WorkflowStateType.PUBLISHED && state.type != WorkflowStateType.ADVERTISED) {
            error("please mark as ready before transitioning to a new state")
        }

        val lookupStateId = if (phase == TransitionPhase.EXIT) item.workflowStateId else request.stateId
        val targetState = stateService.get(lookupStateId) ?: error("state doesn't exist: $lookupStateId")

        val jobName = when (phase) {
            TransitionPhase.DEFAULT -> targetState.jobName
            TransitionPhase.ENTER -> transition.enterJobName
            TransitionPhase.EXIT -> transition.exitJobName
        }

        val resolvedJobName = jobName ?: if (phase == TransitionPhase.DEFAULT) ops.fallbackJobName else return null

        val configuration = buildJobConfiguration(transition, targetState.configuration, item, request.languageTag)

        val factory = provide<JobConfigurationEnqueuer>(name = resolvedJobName)
        val initializer: suspend Job.() -> Unit = {
            addCallback(JobCallback(UpdateContentJobHistoryOnCompleteListener::class))
        }

        val (jobId, effectiveDelayedUntil) = enqueue(factory, configuration, delayUntil, initializer)

        return ops.addHistory(principal, resolvedJobName, jobId, request.languageTag, effectiveDelayedUntil)
    }

    private fun buildJobConfiguration(
        transition: Transition,
        stateConfiguration: JsonElement,
        item: ContentItem,
        languageTag: String?,
    ): JsonObject {
        val languageTagEntries = languageTag?.let { mapOf("languageTag" to JsonPrimitive(it)) } ?: emptyMap()
        return JsonObject(
            (transition.configuration ?: JsonObject(emptyMap())).jsonObject +
                    (stateConfiguration.takeIf { it != JsonNull }?.jsonObject ?: JsonObject(emptyMap())) +
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(item.id.toString()),
                            "version" to JsonPrimitive(item.version),
                            "languageTag" to JsonPrimitive(languageTag),
                            "type" to JsonPrimitive(
                                when (item) {
                                    is ICollection -> "collection"
                                    is Metadata -> "metadata"
                                    else -> error("unsupported content item type: $item")
                                }
                            ),
                        ) + languageTagEntries
                    )
        )
    }


    /**
     * Enqueues a job either immediately or with a delay, returning the job ID and the
     * effective delayed-until timestamp (null if the job was enqueued immediately).
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun enqueue(
        factory: JobConfigurationEnqueuer,
        configuration: JsonObject,
        delayUntil: OffsetDateTime?,
        initializer: suspend Job.() -> Unit,
    ): Pair<UUID, OffsetDateTime?> {
        if (delayUntil == null) {
            return factory.enqueue(configuration, initializer).getId() to null
        }
        val now = OffsetDateTime.now()
        if (delayUntil <= now) {
            return factory.enqueue(configuration, initializer).getId() to null
        }
        val timeout = delayUntil.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
        if (timeout <= 0) {
            return factory.enqueue(configuration, initializer).getId() to null
        }
        return factory.enqueueLater(configuration, timeout.milliseconds, initializer).getId() to delayUntil
    }

    companion object {

        private val log = LoggerFactory.getLogger(Transitioner::class.java)

        /**
         * Converts this epoch millisecond timestamp into a future [OffsetDateTime] delay target.
         *
         * @param allowPast when true, returns a delay target even if this epoch is in the past
         * @return an [OffsetDateTime] representing when the delayed action should execute,
         *         or null if the timestamp is in the past and past timestamps are not allowed
         */
        @OptIn(ExperimentalTime::class)
        fun Long.getDelay(allowPast: Boolean = false): OffsetDateTime? {
            val n = System.currentTimeMillis()
            if (allowPast || this > n) {
                val diff = this - n
                return OffsetDateTime.now().plus(diff.milliseconds.toJavaDuration())
            }
            return null
        }

        /**
         * Determines the effective advertised epoch, if any.
         * The advertised epoch is only valid when set (> 0) and preceding the published epoch.
         */
        fun getEffectiveAdvertisedEpoch(advertisedEpoch: Long?, publishedEpoch: Long?): Long? {
            if (advertisedEpoch == null || advertisedEpoch <= 0) return null
            if (publishedEpoch != null && advertisedEpoch >= publishedEpoch) return null
            return advertisedEpoch
        }

        /**
         * Reads an epoch-millisecond attribute from a content item's JSON attributes.
         * Tolerates both numeric and string-encoded values.
         */
        fun JsonElement?.epochAttribute(key: String): Long? =
            ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let {
                it.longOrNull ?: it.content.toLongOrNull()
            }
    }
}
