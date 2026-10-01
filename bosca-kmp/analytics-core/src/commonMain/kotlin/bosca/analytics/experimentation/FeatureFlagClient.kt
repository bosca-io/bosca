package bosca.analytics.experimentation

import bosca.analytics.api.AnalyticsChildScope
import bosca.analytics.experimentation.persistence.FeatureFlagCacheKey
import bosca.analytics.experimentation.persistence.FeatureFlagCacheStore
import bosca.analytics.delivery.AnalyticsLogger
import bosca.core.analytics.InstallationIdProvider
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.GraphQLSubscriptionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Fetches, caches, observes, and optionally synchronizes Bosca feature flags. */
class FeatureFlagClient(
    private val options: FeatureFlagOptions,
    client: GraphQLClient,
    subscriptions: GraphQLSubscriptionClient,
    private val cacheStore: FeatureFlagCacheStore,
    private val installationIdProvider: InstallationIdProvider,
    scope: CoroutineScope,
    private val logger: AnalyticsLogger,
) {
    private val ownedScope = AnalyticsChildScope(scope)
    private val scope = ownedScope.scope
    private val service = FeatureFlagService(client, installationIdProvider)
    private val mutableFlags = MutableStateFlow<Map<String, FeatureFlag>>(emptyMap())
    private val listeners = MutableStateFlow<List<(Map<String, FeatureFlag>) -> Unit>>(emptyList())
    private val dirtyFlags = MutableStateFlow<Set<String>>(emptySet())
    private val realtime = FeatureFlagRealtime(
        options = options,
        subscriptions = subscriptions,
        scope = scope,
        logger = logger,
        retryPending = ::retryDirtyFlags,
        deleteFlag = ::deleteFlag,
        refreshFlag = ::refreshFlag,
    )
    private var initialized = false
    private var realtimeConsumers = 0

    val flags: StateFlow<Map<String, FeatureFlag>> = mutableFlags.asStateFlow()

    suspend fun loadCache(): Boolean {
        val cached = cacheStore.load(cacheKey()) ?: return false
        restoreCache(cached)
        return true
    }

    fun getCache(): List<FeatureFlag> = mutableFlags.value.values.toList()

    fun restoreCache(evaluations: List<FeatureFlag>) {
        mutableFlags.value = evaluations.associateBy { it.flagKey }
        initialized = true
        notifyListeners()
    }

    suspend fun initialize() {
        mutableFlags.value = service.evaluateAll().associateBy { it.flagKey }
        initialized = true
        persist()
        notifyListeners()
        if (realtimeConsumers > 0) realtime.start()
    }

    suspend fun refresh() = initialize()

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean =
        mutableFlags.value[key]?.value?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: defaultValue

    fun getString(key: String, defaultValue: String = ""): String =
        mutableFlags.value[key]?.value?.let { (it as? JsonPrimitive)?.contentOrNull } ?: defaultValue

    fun getNumber(key: String, defaultValue: Double = 0.0): Double =
        mutableFlags.value[key]?.value?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: defaultValue

    fun getJson(key: String): JsonElement? = mutableFlags.value[key]?.value

    fun getFlag(key: String): FeatureFlag? = mutableFlags.value[key]

    /** Registers a callback and returns an unsubscribe function. */
    fun onChange(listener: (Map<String, FeatureFlag>) -> Unit): () -> Unit {
        listeners.value = listeners.value + listener
        return { listeners.value = listeners.value.filterNot { it === listener } }
    }

    fun addRealtimeListener() {
        realtimeConsumers++
        if (realtimeConsumers == 1 && initialized) realtime.start()
    }

    fun removeRealtimeListener() {
        if (realtimeConsumers == 0) return
        realtimeConsumers--
        if (realtimeConsumers == 0) realtime.stop()
    }

    fun startListening() = realtime.start()

    fun stopListening() = realtime.stop()

    fun close() {
        realtime.stop()
        ownedScope.close()
    }

    suspend fun refreshFlag(flagKey: String) {
        try {
            val evaluation = service.evaluate(flagKey)
            mutableFlags.value = mutableFlags.value + (evaluation.flagKey to evaluation)
            dirtyFlags.update { it - flagKey }
            persistAndNotify()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            dirtyFlags.update { it + flagKey }
            logger.log("[bosca-analytics] failed to refresh '$flagKey'; queued for retry", error)
        }
    }

    private suspend fun deleteFlag(flagKey: String) {
        mutableFlags.value = mutableFlags.value - flagKey
        dirtyFlags.update { it - flagKey }
        persistAndNotify()
    }

    private suspend fun retryDirtyFlags() {
        dirtyFlags.value.forEach { refreshFlag(it) }
    }

    private suspend fun persistAndNotify() {
        persist()
        notifyListeners()
    }

    private suspend fun persist() {
        cacheStore.save(cacheKey(), getCache())
    }

    private suspend fun cacheKey() = FeatureFlagCacheKey(
        installationId = installationIdProvider.getOrCreate(),
        identity = options.identity(),
    )

    private fun notifyListeners() {
        val snapshot = mutableFlags.value
        listeners.value.forEach { listener ->
            try {
                listener(snapshot)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.log("[bosca-analytics] feature flag listener failed", error)
            }
        }
    }
}
