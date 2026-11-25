package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** Binds every child operation to the harness conversation and its existing scoped transport. */
class DelegationApi(network: NetworkClient, private val sessionId: Uuid) : Api(network) {
    suspend fun settings() = network.boscaGraphql.execute(KitDelegationSettings, KitDelegationSettings.Variables(sessionId)).ai.delegations.settings
    suspend fun start(input: KitDelegationInput): IKitDelegationFragment = network.boscaGraphql.execute(
        StartKitDelegation, StartKitDelegation.Variables(sessionId, input),
    ).ai.delegations.start
    suspend fun get(id: Uuid): IKitDelegationFragment? = network.boscaGraphql.execute(
        GetKitDelegation, GetKitDelegation.Variables(sessionId, id),
    ).ai.delegations.get
    suspend fun list(offset: Long, limit: Int): List<IKitDelegationFragment> = network.boscaGraphql.execute(
        ListKitDelegations, ListKitDelegations.Variables(sessionId, offset, limit),
    ).ai.delegations.tasks

    /**
     * Persists the wait once, then makes at most 20 short reads within 25 seconds and returns the last observed
     * state; unfinished work remains discoverable by ID.
     */
    suspend fun wait(id: Uuid): IKitDelegationFragment {
        val started = TimeSource.Monotonic.markNow()
        fun remaining() = WAIT_LIMIT - started.elapsedNow()
        var child: IKitDelegationFragment = withTimeoutOrNull(remaining()) {
            network.boscaGraphql.execute(WaitKitDelegation, WaitKitDelegation.Variables(sessionId, id)).ai.delegations.wait
        } ?: error("Bosca did not acknowledge the wait for child task $id within $WAIT_LIMIT")
        var reads = 0
        while (child.status in setOf(KitDelegationStatus.QUEUED, KitDelegationStatus.RUNNING) && reads++ < MAX_READS && remaining().isPositive()) {
            delay(minOf(1.seconds, remaining()))
            child = withTimeoutOrNull(remaining()) { requireNotNull(get(id)) { "Child task no longer exists" } } ?: return child
        }
        return child
    }

    suspend fun cancel(id: Uuid): IKitDelegationFragment = network.boscaGraphql.execute(
        CancelKitDelegation, CancelKitDelegation.Variables(sessionId, id),
    ).ai.delegations.cancel
    suspend fun submit(output: KitDelegationResultInput): IKitDelegationFragment = network.boscaGraphql.execute(
        SubmitKitDelegation, SubmitKitDelegation.Variables(sessionId, output),
    ).ai.delegations.submit

    private companion object {
        val WAIT_LIMIT = 25.seconds
        const val MAX_READS = 20
    }
}
