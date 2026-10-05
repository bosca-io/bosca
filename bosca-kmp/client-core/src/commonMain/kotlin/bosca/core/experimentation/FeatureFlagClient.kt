package bosca.core.experimentation

import bosca.core.graphql.EvaluateAllFlags
import bosca.core.graphql.EvaluateFlag
import bosca.core.graphql.FlagUpdateAction
import bosca.core.graphql.FlagUpdated
import bosca.core.platform.Log
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.GraphQLSubscriptionClient
import bosca.graphql.client.execute
import bosca.graphql.client.subscribe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Resolved feature flag value for the current user/device context.
 */
data class FlagEvaluation(
    val flagKey: String,
    val value: JsonElement,
    val variantKey: String? = null,
    val experimentId: kotlin.uuid.Uuid? = null,
)

/**
 * Client-side feature flag evaluation and real-time synchronization for Compose Multiplatform applications.
 *
 * Fetches all flag evaluations from the server via the Bosca GraphQL client ([GraphQLClient]) on
 * initialization, then subscribes to real-time updates via a GraphQL subscription
 * ([GraphQLSubscriptionClient]). Flag values are exposed as [StateFlow] for reactive consumption in Compose UI.
 *
 * ## Lifecycle
 *
 * Call sites MUST follow this three-step lifecycle in order; skipping a step is a bug:
 *
 *  1. **`initialize()`** — issues a single GraphQL query to populate the reactive `flags` map with current
 *     evaluations. Suspending; call once at startup before any UI reads `getBoolean` / `rememberFlag`,
 *     otherwise the UI will see only `default` values until the first subscription push lands.
 *  2. **`startListening()`** — launches a long-lived coroutine that subscribes to `flagUpdated` events and
 *     refreshes individual flags as they change. Non-suspending; the subscription runs on the internal [scope]
 *     and reconnects with exponential backoff if the transport drops.
 *  3. **`close()`** — cancels the internal [scope], tears down the subscription, and stops all reconnect
 *     attempts. Failing to call `close()` leaks coroutines and the underlying WebSocket connection.
 *
 * The [reconnectAttempts] counter is mutated only inside the single `scope.launch` started by
 * [startListening], so all reads/writes happen on the same coroutine and there is no cross-thread race
 * despite the `var` declaration.
 *
 * @param graphql the Bosca GraphQL client for queries
 * @param subscriptions the Bosca GraphQL subscription client for the `flagUpdated` stream
 * @param installationId the device installation ID for anonymous bucketing
 * @param maxReconnectAttempts upper bound on subscription reconnect tries before giving up; defaults to
 *   [DEFAULT_MAX_RECONNECT_ATTEMPTS]. Exposed as a constructor parameter so tests and embedded uses can set a
 *   tighter cap.
 * @param baseReconnectDelayMs initial backoff delay in milliseconds; doubled on each failed reconnect up to
 *   [maxReconnectDelayMs].
 * @param maxReconnectDelayMs maximum backoff delay in milliseconds.
 */
class FeatureFlagClient(
    private val graphql: GraphQLClient,
    private val subscriptions: GraphQLSubscriptionClient,
    private val installationId: String,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val baseReconnectDelayMs: Long = DEFAULT_BASE_RECONNECT_DELAY_MS,
    private val maxReconnectDelayMs: Long = DEFAULT_MAX_RECONNECT_DELAY_MS,
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)
    private val _flags = MutableStateFlow<Map<String, FlagEvaluation>>(emptyMap())

    /**
     * Reconnect attempts since the last successful subscription event. Confined to the single coroutine
     * launched by [startListening], so all reads/writes happen on the same coroutine and the `var` is race-free
     * without additional synchronization.
     */
    private var reconnectAttempts = 0

    /**
     * Set of flag keys whose last `refreshFlag` call failed. Each entry is retried on the next subscription push
     * and on the next successful reconnect, so the cache cannot stay silently stale after a transient
     * HTTP/GraphQL error.
     *
     * Guarded by [dirtyFlagsMutex] because `initialize()` clears the set while `startListening`'s coroutine may
     * be mutating it concurrently.
     */
    private val dirtyFlags: MutableSet<String> = mutableSetOf()
    private val dirtyFlagsMutex = Mutex()

    /** A reactive map of all flag evaluations, keyed by flag key. */
    val flags: StateFlow<Map<String, FlagEvaluation>> = _flags.asStateFlow()

    /**
     * Fetches all active flag evaluations from the server and populates the reactive [flags] map. Throws on
     * failure (the client surfaces GraphQL errors / no-data as a thrown exception) so callers can handle it
     * (e.g. retry, show a fallback UI, or log).
     */
    suspend fun initialize() {
        val data = graphql.execute(EvaluateAllFlags, EvaluateAllFlags.Variables(installationId = installationId))
        _flags.value = data.featureFlags.evaluateAll.associate { eval ->
            eval.flagKey to FlagEvaluation(
                flagKey = eval.flagKey,
                value = eval.value,
                variantKey = eval.variationKey,
                experimentId = eval.experimentId,
            )
        }
        dirtyFlagsMutex.withLock { dirtyFlags.clear() }
    }

    /**
     * Re-evaluates all feature flags. Call this after a user logs in or out so the local cache reflects the new
     * user's targeting rules; the subscription's next reconnect picks up the new auth token automatically.
     */
    suspend fun refresh() {
        initialize()
    }

    /**
     * Subscribes to real-time flag update events via GraphQL subscription. When a flag is updated, its
     * evaluation is refreshed from the server; when deleted, it is removed from the local cache.
     *
     * Automatically reconnects with exponential backoff on subscription failure, capped at [maxReconnectDelayMs]
     * with a maximum of [maxReconnectAttempts].
     */
    fun startListening() {
        scope.launch {
            while (reconnectAttempts < maxReconnectAttempts) {
                try {
                    subscriptions.subscribe(FlagUpdated, Unit).collect { data ->
                        reconnectAttempts = 0
                        // Opportunistically retry any flags that previous refresh attempts failed to fetch. A
                        // successful subscription push means the server is reachable again, so this is the
                        // cheapest place to drain the dirty set without introducing a timer.
                        retryDirtyFlags()
                        val update = data.flagUpdated
                        when (update.action) {
                            FlagUpdateAction.DELETED -> {
                                _flags.value = _flags.value - update.flagKey
                                dirtyFlagsMutex.withLock { dirtyFlags.remove(update.flagKey) }
                            }
                            FlagUpdateAction.UPDATED -> refreshFlag(update.flagKey)
                        }
                    }
                    // Flow completed normally (server closed) — reconnect
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("FeatureFlagClient: subscription error, reconnecting: ${e.message}")
                }
                val delayMs = min(
                    baseReconnectDelayMs * (1L shl reconnectAttempts),
                    maxReconnectDelayMs,
                )
                reconnectAttempts++
                delay(delayMs.milliseconds)
            }
            Log.w("FeatureFlagClient: exhausted $maxReconnectAttempts reconnect attempts — real-time updates stopped. Flag values may become stale.")
        }
    }

    /**
     * Cancels all coroutines and closes the subscription. Must be called when the client is no longer needed to
     * avoid leaking coroutines and WebSocket connections.
     */
    fun close() {
        scope.cancel()
    }

    /** Returns a boolean flag value, defaulting if the flag is not found. */
    fun getBoolean(key: String, default: Boolean = false): Boolean {
        val flag = _flags.value[key] ?: return default
        return (flag.value as? JsonPrimitive)?.booleanOrNull ?: default
    }

    /** Returns a string flag value, defaulting if the flag is not found. */
    fun getString(key: String, default: String = ""): String {
        val flag = _flags.value[key] ?: return default
        val primitive = flag.value as? JsonPrimitive ?: return default
        return if (primitive.isString) primitive.content else default
    }

    /** Returns the raw JSON flag value, or null if not found. */
    fun getJson(key: String): JsonElement? {
        return _flags.value[key]?.value
    }

    private suspend fun refreshFlag(flagKey: String) {
        try {
            val eval = graphql.execute(
                EvaluateFlag,
                EvaluateFlag.Variables(flagKey = flagKey, installationId = installationId),
            ).featureFlags.evaluate
            val updated = _flags.value.toMutableMap()
            updated[eval.flagKey] = FlagEvaluation(
                flagKey = eval.flagKey,
                value = eval.value,
                variantKey = eval.variationKey,
                experimentId = eval.experimentId,
            )
            _flags.value = updated
            dirtyFlagsMutex.withLock { dirtyFlags.remove(flagKey) }
        } catch (e: Exception) {
            // Mark the flag dirty so the next subscription push retries it; if the WebSocket itself is dead, the
            // next reconnect will run the recovery via [retryDirtyFlags] above. Without this the local cache
            // would silently believe it was in sync with the server and keep serving the old value forever.
            dirtyFlagsMutex.withLock { dirtyFlags.add(flagKey) }
            Log.w("FeatureFlagClient: failed to refresh flag '$flagKey' (queued for retry): ${e.message}")
        }
    }

    /**
     * Retries every flag in [dirtyFlags] one at a time. Called from the subscription collect block whenever a
     * successful event lands — that's the cheapest signal that the network is working again, so piggy-backing
     * the recovery on subscription traffic avoids needing a separate timer.
     */
    private suspend fun retryDirtyFlags() {
        // Snapshot before iterating because refreshFlag mutates the set.
        val snapshot = dirtyFlagsMutex.withLock {
            if (dirtyFlags.isEmpty()) return
            dirtyFlags.toList()
        }
        for (key in snapshot) {
            refreshFlag(key)
        }
    }

    companion object {
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS: Int = 20
        const val DEFAULT_BASE_RECONNECT_DELAY_MS: Long = 1_000L
        const val DEFAULT_MAX_RECONNECT_DELAY_MS: Long = 60_000L
    }
}
