package bosca.analytics.experimentation

import bosca.analytics.delivery.AnalyticsLogger
import bosca.core.graphql.FlagUpdateAction
import bosca.core.graphql.FlagUpdated
import bosca.graphql.client.GraphQLSubscriptionClient
import bosca.graphql.client.subscribe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

internal class FeatureFlagRealtime(
    private val options: FeatureFlagOptions,
    private val subscriptions: GraphQLSubscriptionClient,
    private val scope: CoroutineScope,
    private val logger: AnalyticsLogger,
    private val retryPending: suspend () -> Unit,
    private val deleteFlag: suspend (String) -> Unit,
    private val refreshFlag: suspend (String) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null

    fun start() {
        stop()
        val currentGeneration = ++generation
        job = scope.launch {
            reconnect(currentGeneration)
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
    }

    private suspend fun reconnect(currentGeneration: Long) {
        var attempt = 0
        while (currentGeneration == generation) {
            try {
                subscriptions.subscribe(FlagUpdated, Unit).collect { data ->
                    if (currentGeneration != generation) return@collect
                    attempt = 0
                    retryPending()
                    val update = data.flagUpdated
                    when (update.action) {
                        FlagUpdateAction.DELETED -> deleteFlag(update.flagKey)
                        FlagUpdateAction.UPDATED -> refreshFlag(update.flagKey)
                    }
                }
                attempt = 0
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.log("[bosca-analytics] feature flag subscription failed", error)
            }
            if (attempt >= options.maxReconnectAttempts) break
            val multiplier = 2.0.pow(attempt.toDouble())
            val reconnectDelay = minOf(
                (options.baseReconnectDelay.inWholeMilliseconds * multiplier).toLong().milliseconds,
                options.maxReconnectDelay,
            )
            attempt++
            delay(reconnectDelay)
        }
    }
}
