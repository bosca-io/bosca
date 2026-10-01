package bosca.content.transition.jobs

import bosca.content.collection.model.ContentItem
import bosca.content.collection.model.ICollection
import bosca.content.metadata.model.Metadata
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.TransitionScheduleResolver.epochAttribute
import bosca.content.transition.service.TransitionScheduleResolver.toFutureDelay
import bosca.content.transition.service.Transitioner
import bosca.content.transition.service.TransitioningService
import bosca.db.connection
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.DelayException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

/**
 * Shared transition execution logic used by both [MetadataTransitionExecutor] and
 * [CollectionTransitionExecutor]. Both content types follow the same lifecycle:
 *
 * 1. If deleted, return immediately
 * 2. Handle the processing→draft auto-transition (pending+processing → processing → draft)
 * 3. Enforce stateValid delay (re-queue if the scheduled time hasn't arrived)
 * 4. Complete the pending state transition
 * 5. If now in "advertised" state, chain to "published" via the transitioner
 *
 * The advertised→published chain in step 5 is also invoked from
 * [UpdateContentJobHistoryOnCompleteListener] — workflow states configured with
 * their own job (e.g. `multi-job`) never route through these executors, so the
 * listener performs the same chaining when it finalizes a transition into
 * "advertised". Keep both call sites in mind when changing [chainToPublished].
 */
object ContentTransitionLogic {

    /**
     * Executes the transition lifecycle for a content item loaded by the caller.
     *
     * @param item the content item being transitioned (freshly loaded from DB)
     * @param principal the principal performing the transition
     * @param authenticationContext the security context for chaining further transitions
     * @param transitioner the transitioner instance for advertised→published chaining
     * @param transitioningService the type-specific service for intermediate state mutations
     *   (processing→draft dance), typically with events suppressed
     * @param completionService the service used for the final state completion, typically
     *   with events enabled so downstream jobs (e.g. MetadataSyncStateJob) are enqueued
     * @param getWorkflowStateValid extracts the stateValid timestamp from the content item
     * @param languageTag optional language tag for collection variants
     * @return the content item after the transition has been processed
     * @throws DelayException if the stateValid timestamp has not yet been reached
     */
    suspend fun <T : ContentItem> execute(
        item: T,
        principal: Principal,
        authenticationContext: AuthenticationContext,
        transitioner: Transitioner,
        transitioningService: TransitioningService<T>,
        completionService: TransitioningService<T> = transitioningService,
        getWorkflowStateValid: (T) -> OffsetDateTime?,
        languageTag: String?,
    ): ContentItem {
        var current = item

        if (current.isDeleted) return current

        current = handleProcessingTransition(current, principal, transitioningService)

        if (current.workflowStatePendingId == null) {
            // A redelivered job whose completion already committed: the advertised →
            // published chain runs after the completion commit, so a chain failure on
            // the first delivery leaves the item settled in "advertised" with nothing
            // scheduled. Re-run the chain instead of silently reporting success —
            // once the chain has succeeded the item carries a pending "published"
            // state and no longer takes this branch.
            if (current.workflowStateId == "advertised") {
                return chainToPublished(current, authenticationContext, transitioner, languageTag)
            }
            return current
        }

        enforceStateValidDelay(getWorkflowStateValid(current))

        current = completionService.setPendingStateComplete(current, "Transition Complete", principal)

        if (current.workflowStateId == "advertised") {
            return chainToPublished(current, authenticationContext, transitioner, languageTag)
        }

        return current
    }

    private suspend fun <T : ContentItem> handleProcessingTransition(
        item: T,
        principal: Principal,
        transitioningService: TransitioningService<T>,
    ): T {
        var current = item

        if (current.workflowStateId == "pending" && current.workflowStatePendingId == "processing") {
            current = transitioningService.setPendingStateComplete(current, "Transition Complete", principal)
        }

        if (current.workflowStateId == "processing" && current.workflowStatePendingId == null) {
            current = transitioningService.setPendingState(
                current, "draft",
                status = "Moving from processing to draft",
                principal = principal,
                notifyEvent = false
            )
        }

        return current
    }

    private fun enforceStateValidDelay(stateValid: OffsetDateTime?) {
        stateValid ?: return
        val now = Clock.System.now()
        val then = stateValid.toInstant().toKotlinInstant()
        val remaining = then - now
        if (remaining > 1.seconds) {
            throw DelayException(remaining)
        }
    }

    /**
     * Chains a content item that has just settled in the "advertised" state into a
     * "published" transition, delayed until the item's `published` epoch attribute
     * when that timestamp is still in the future (immediate otherwise).
     *
     * Commits any open transaction first — the advertised state must be durable
     * before the next transition begins, and [Transitioner.beginTransition] refuses
     * to run inside a transaction.
     *
     * Shared by the transition executors (via [execute]) and
     * [UpdateContentJobHistoryOnCompleteListener] (for workflow states that run
     * their own job instead of the fallback transition executors).
     */
    suspend fun chainToPublished(
        item: ContentItem,
        authenticationContext: AuthenticationContext,
        transitioner: Transitioner,
        languageTag: String?,
    ): ContentItem {
        val published = item.attributes.epochAttribute("published")
        val delay = published?.toFutureDelay()

        connection().commitTransaction()

        return transitioner.beginTransition(
            authenticationContext,
            when (item) {
                is Metadata -> BeginTransitionInput(
                    metadataId = item.id,
                    version = item.version,
                    stateValid = delay,
                    stateId = "published",
                    status = "Advertisement Complete",
                )

                is ICollection -> BeginTransitionInput(
                    collectionId = item.id,
                    languageTag = languageTag,
                    stateValid = delay,
                    stateId = "published",
                    status = "Advertisement Complete",
                )

                else -> error("unsupported content item type: $item")
            }
        )
    }

    private val ContentItem.isDeleted: Boolean
        get() = (this as? Metadata)?.deleted == true
}
